"""FC04 owned disposable HTTP qualification/receipt checks; no funds or customer data."""
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
    login_name = 'fc04-' + secrets.token_hex(8)
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
            schema = env['MYSQL_TEST_URL'].split('/')[-1].split('?')[0]

            def sql(statement):
                result = subprocess.run(client, input=isolated.guarded_sql(directory,
                    f'USE {schema};\n' + statement), env=client_env, text=True,
                    encoding='utf-8', capture_output=True, check=True, creationflags=FLAGS)
                return result.stdout.strip().splitlines()[-1]

            buyer = call('POST', '/auth/dev-login', body={'fixture': 'buyer'})['accessToken']
            promoter = call('POST', '/auth/dev-login', body={'fixture': 'distributor-a'})['accessToken']
            uid = call('GET', '/me', promoter)['id']
            for suffix in ['summary', 'commissions', 'withdrawals']:
                call('GET', '/distribution/' + suffix, buyer, expected=404,
                     code='DISTRIBUTOR_NOT_ENABLED', record='ordinary-' + suffix)
            call('POST', '/distribution/receipt-profile', buyer, {}, 404,
                 'DISTRIBUTOR_NOT_ENABLED', 'ordinary-bind-denied')
            assert sql('SELECT COUNT(*) FROM distributor;') == '0'
            profile = call('POST', '/admin/distributors', admin,
                           {'userId': uid, 'reason': 'FC04 isolated legal qualification'}, record='qualification')
            did = profile['id']
            product = call('POST', '/admin/products', admin, {
                'version': 0, 'type': 'BOOK', 'title': '分销核验·仅隔离图书', 'priceFen': 2500,
                'categoryIds': [], 'contentBlocks': [], 'sortNo': 0,
                'book': {'quantityLimit': 9, 'shippingSummary': '仅隔离测试'},
            })
            pid = product['id']
            call('POST', f'/admin/products/{pid}/publish', admin,
                 {'version': product['version'], 'reason': 'FC04 isolated sample'})
            call('PUT', f'/admin/products/{pid}/distributors', admin,
                 {'version': 0, 'rules': [{'distributorId': did, 'rateBps': 1250, 'enabled': True}],
                  'reason': 'FC04 isolated authorized rule'}, record='product-rule')
            summary = call('GET', '/distribution/summary', promoter, record='active-bound')
            assert summary['status'] == 'ACTIVE' and summary['receiptReady'] is True
            assert summary['promotableProductCount'] == 1
            for suffix in ['commissions', 'withdrawals']:
                page = call('GET', '/distribution/' + suffix, promoter, record='active-' + suffix)
                assert page['total'] == 0
            sql(f"UPDATE distributor SET receipt_ready=false WHERE id={int(did)};")
            summary = call('GET', '/distribution/summary', promoter, record='active-unbound')
            assert summary['receiptReady'] is False and summary['withdrawalEnabled'] is False
            assert summary['disabledReason'] == 'WITHDRAWAL_NOT_READY'
            call('POST', '/distribution/receipt-profile', promoter, {'realName': '隔离测试'},
                 503, 'WITHDRAWAL_NOT_READY', 'dev-binding-failure')
            call('PATCH', '/admin/distributors/' + did, admin,
                 {'version': profile['version'], 'status': 'DISABLED', 'riskBlocked': False,
                  'reason': 'FC04 isolated disabled qualification'}, record='disable-profile')
            sql(f"UPDATE distributor SET receipt_ready=true WHERE id={int(did)};")
            summary = call('GET', '/distribution/summary', promoter, record='disabled-bound')
            assert summary['receiptReady'] is True and summary['status'] == 'DISABLED'
            assert summary['withdrawalEnabled'] is False and summary['disabledReason'] == 'DISTRIBUTOR_DISABLED'
            call('POST', '/distribution/receipt-profile', promoter, {}, 403,
                 'DISTRIBUTOR_DISABLED', 'disabled-bind-denied')
            for suffix in ['commissions', 'withdrawals']:
                call('GET', '/distribution/' + suffix, promoter, record='disabled-' + suffix)
            assert sql('SELECT COUNT(*) FROM withdrawal_request;') == '0'
            assert sql('SELECT COUNT(*) FROM store_order;') == '0'
            report = {'status': 'PASS', 'database': 'owned-disposable', 'port': port,
                      'httpChecks': len(checks), 'customerDataChanged': False, 'ordersCreated': 0,
                      'withdrawalsCreated': 0, 'realChannelInvoked': False, 'jvmPid': proc.pid}
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
    parser.add_argument('--output', type=Path, default=ROOT/'target/smoke/2026-10-14-fc04')
    args = parser.parse_args()
    args.goals = ['-DskipTests', 'package']
    raise SystemExit(isolated.run(args, functools.partial(smoke, args)))
