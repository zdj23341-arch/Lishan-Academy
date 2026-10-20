"""FC-03 real HTTP checks on an owned disposable MySQL and loopback JVM.

No customer configuration, address, order, stock or payment is changed. Test rules
are not deployment defaults. Only deliberately stale order creation is attempted;
the runner verifies rejection and zero new orders, with no payment calls.
"""
import functools
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid

import with_local_mysql as isolated
from maven import FLAGS, find_java, run_command

ROOT = Path(__file__).resolve().parents[2]


def smoke(args, build, env, client, client_env, directory):
    if run_command(build, env):
        raise RuntimeError('Backend packaging failed')
    jars = list((ROOT / 'backend/target').glob('jxsc-backend-*.jar'))
    if len(jars) != 1:
        raise RuntimeError('Expected one backend JAR')
    args.output.mkdir(parents=True, exist_ok=True)
    app_env = {k: v for k, v in env.items()
               if not k.upper().startswith(('JXSC_', 'ADMIN_BOOTSTRAP_'))
               and not k.upper().endswith('_PROVIDER_MODE')}
    login_name = 'fc03-' + secrets.token_hex(8)
    password = 'Isolated-' + secrets.token_urlsafe(24) + '!42'
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    app_env['SPRING_APPLICATION_JSON'] = json.dumps({
        'spring.profiles.active': 'test',
        'spring.config.location': 'classpath:/application.yml,classpath:/application-test.yml',
        'jxsc.tasks.enabled': False, 'server.address': '127.0.0.1', 'server.port': port,
        'ADMIN_BOOTSTRAP_LOGIN': login_name, 'ADMIN_BOOTSTRAP_PASSWORD': password,
    })
    java = find_java(args.java_home, app_env)
    settings = subprocess.run([str(java), '-XshowSettings:properties', '-version'],
                              capture_output=True, text=True, encoding='utf-8', errors='replace',
                              env=app_env, creationflags=FLAGS, check=True)
    home = re.search(r'^\s*java.home = (.+)$', settings.stderr + settings.stdout, re.M)
    actual = Path(home.group(1).strip())/'bin'/('java.exe' if os.name == 'nt' else 'java') if home else None
    if not actual or not actual.is_file():
        raise RuntimeError('Cannot identify owned JVM')
    checks = {}

    def call(method, path, token=None, body=None, expected=200, code=None, record=None, key=None):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        if key:
            headers['Idempotency-Key'] = key
        req = urllib.request.Request(f'http://127.0.0.1:{port}/api/v1{path}', method=method,
                                     headers=headers, data=None if body is None else json.dumps(body).encode())
        try:
            with urllib.request.urlopen(req, timeout=15) as res:
                status, response = res.status, json.load(res)
        except urllib.error.HTTPError as res:
            status, response = res.code, json.load(res)
        assert status == expected, (method, path, status, response.get('code'))
        if code:
            assert response['code'] == code, (path, response['code'])
        if record:
            checks[record] = {'method': method, 'path': path, 'status': status,
                              'request': body, 'response': response}
        return response['data'] if status == 200 else response

    with (args.output/'isolated-server.log').open('wb') as log:
        proc = subprocess.Popen([str(actual.resolve()), '-jar', str(jars[0])], env=app_env,
                                cwd=ROOT/'backend', stdout=log, stderr=subprocess.STDOUT, creationflags=FLAGS)
        try:
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                if proc.poll() is not None:
                    raise RuntimeError('Isolated server exited')
                try:
                    call('GET', '/health')
                    break
                except OSError:
                    pass
                time.sleep(.5)
            else:
                raise RuntimeError('Isolated server readiness timed out')
            # Health may be available before ApplicationRunner commits bootstrap.
            schema = env['MYSQL_TEST_URL'].split('/')[-1].split('?')[0]
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                result = subprocess.run(client, input=isolated.guarded_sql(directory,
                    f"USE {schema}; SELECT count(*) FROM admin_user WHERE login_name='{login_name}';"),
                    env=client_env, text=True, encoding='utf-8', capture_output=True,
                    check=True, creationflags=FLAGS, timeout=5)
                if result.stdout.splitlines()[-1] == '1':
                    break
                time.sleep(.25)
            else:
                raise RuntimeError('Isolated bootstrap has not committed')
            admin = call('POST', '/admin/auth/login', body={'loginName': login_name, 'password': password})['accessToken']
            new_password = 'Changed-' + secrets.token_urlsafe(24) + '!42'
            call('POST', '/admin/auth/change-password', admin,
                 {'currentPassword': password, 'newPassword': new_password})
            admin = call('POST', '/admin/auth/login', body={'loginName': login_name, 'password': new_password})['accessToken']
            buyer = call('POST', '/auth/dev-login', body={'fixture': 'buyer'})['accessToken']
            product = call('POST', '/admin/products', admin, {
                'version': 0, 'type': 'BOOK', 'title': '配送验收·仅隔离图书', 'priceFen': 2500,
                'categoryIds': [], 'contentBlocks': [], 'sortNo': 0,
                'book': {'quantityLimit': 9, 'shippingSummary': '仅隔离测试，不是客户正式运费'},
            })
            pid = product['id']
            call('POST', f'/admin/products/{pid}/inventory-adjustments', admin,
                 {'delta': 20, 'reason': 'FC03 isolated stock'}, key=str(uuid.uuid4()))
            call('POST', f'/admin/products/{pid}/publish', admin,
                 {'version': product['version'], 'reason': 'FC03 isolated sample'})
            addresses = {}
            for province, city, district in [('310000', '310100', '310101'),
                                              ('110000', '110100', '110101'),
                                              ('320000', '320100', '320102')]:
                addresses[province] = call('POST', '/addresses', buyer, {
                    'version': 0, 'name': '隔离验收收件人', 'phone': '13800000000',
                    'provinceCode': province, 'cityCode': city, 'districtCode': district,
                    'detail': '仅隔离样本地址', 'isDefault': province == '310000',
                })

            def preview(province, quantity, name, expected=200, code=None):
                return call('POST', '/orders/preview', buyer,
                            {'productId': pid, 'quantity': quantity, 'addressId': addresses[province]['id']},
                            expected, code, name)

            preview('110000', 1, 'missing-region', 409, 'SHIPPING_UNAVAILABLE')
            rule = call('PUT', '/admin/shipping-rules/310000', admin,
                        {'version': 0, 'enabled': True, 'freightFen': 600,
                         'freeThresholdFen': 10000, 'reason': 'FC03 isolated rule'}, record='enabled-rule')
            readback = call('GET', '/admin/shipping-rules?page=1&pageSize=100', admin, record='rule-readback')
            assert any(r['provinceCode'] == '310000' and r['version'] == rule['version']
                       and r['freightFen'] == 600 for r in readback['items'])
            call('GET', '/admin/shipping-rules', buyer, expected=403, code='FORBIDDEN', record='buyer-cannot-manage')
            for qty, goods, freight, payable in [(1, 2500, 600, 3100), (3, 7500, 600, 8100),
                                                (4, 10000, 0, 10000), (5, 12500, 0, 12500)]:
                q = preview('310000', qty, f'quantity-{qty}')
                assert (q['goodsAmountFen'], q['freightAmountFen'], q['payableAmountFen']) == (goods, freight, payable)
            call('PUT', '/admin/shipping-rules/320000', admin,
                 {'version': 0, 'enabled': False, 'freightFen': 600,
                  'freeThresholdFen': None, 'reason': 'FC03 isolated disabled region'}, record='disabled-rule')
            preview('320000', 1, 'disabled-region', 409, 'SHIPPING_UNAVAILABLE')
            q = preview('310000', 1, 'before-rule-change')
            changed = call('PUT', '/admin/shipping-rules/310000', admin,
                           {'version': rule['version'], 'enabled': True, 'freightFen': 800,
                            'freeThresholdFen': None, 'reason': 'FC03 isolated version change'}, record='changed-rule')
            assert changed['version'] > rule['version']
            call('POST', '/orders', buyer, {'quoteId': q['quoteId']}, 409, 'ORDER_STALE',
                 'stale-quote-rejected', str(uuid.uuid4()))
            call('PUT', '/admin/shipping-rules/310000', admin,
                 {'version': rule['version'], 'enabled': True, 'freightFen': 900,
                  'freeThresholdFen': None, 'reason': 'FC03 stale admin version'},
                 409, 'VERSION_CONFLICT', 'stale-rule-write-rejected')
            fresh = preview('310000', 5, 'fresh-no-threshold')
            assert (fresh['goodsAmountFen'], fresh['freightAmountFen'], fresh['payableAmountFen']) == (12500, 800, 13300)
            call('PUT', '/admin/shipping-rules/310000', admin,
                 {'version': changed['version'], 'enabled': False, 'freightFen': 800,
                  'freeThresholdFen': None, 'reason': 'FC03 isolated disabling'}, record='disable-previous-rule')
            preview('310000', 1, 'previous-region-now-disabled', 409, 'SHIPPING_UNAVAILABLE')
            orders = call('GET', '/orders?tab=ALL&page=1&pageSize=100', buyer, record='orders-remain-empty')
            assert orders['total'] == 0
            stock = call('GET', f'/products/{pid}', buyer, record='stock-unchanged')
            assert stock['book']['availableStock'] == 20
            report = {'status': 'PASS', 'database': 'owned-disposable', 'port': port,
                      'httpChecks': len(checks), 'customerDataChanged': False, 'ordersCreated': 0,
                      'paymentsRequested': 0, 'actualRulesConfigured': False, 'jvmPid': proc.pid}
            (args.output/'http-evidence.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2), encoding='utf-8')
            (args.output/'http-report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
            print(json.dumps(report), flush=True)
            return 0
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=10)


if __name__ == '__main__':
    parser = isolated.parser(__doc__)
    parser.add_argument('--mysql-bin')
    parser.add_argument('--output', type=Path, default=ROOT/'target/smoke/2026-10-13-fc03')
    args = parser.parse_args()
    args.goals = ['-DskipTests', 'package']
    raise SystemExit(isolated.run(args, functools.partial(smoke, args)))
