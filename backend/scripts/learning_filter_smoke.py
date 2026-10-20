"""Exercise FC-01 HTTP and optionally WeChat against owned disposable MySQL.

Never connects to the customer's database. A private, temporary DEV token is used
only for read requests redirected to this loopback test server and is not archived.
"""
import functools
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

import with_local_mysql as isolated
from maven import FLAGS, find_java, run_command

ROOT = Path(__file__).resolve().parents[2]


def http(port, path, token=None, body=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(f'http://127.0.0.1:{port}{path}',
                                    data=None if body is None else json.dumps(body).encode(), headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as response:
        return response.code, json.load(response)


def smoke(args, build, env, client, client_env, directory):
    if run_command(build, env):
        raise RuntimeError('Backend packaging failed')
    jars = list((ROOT / 'backend/target').glob('jxsc-backend-*.jar'))
    if len(jars) != 1:
        raise RuntimeError('Expected one packaged backend JAR')
    args.output.mkdir(parents=True, exist_ok=True)
    app_env = {k: v for k, v in env.items()
               if not k.upper().startswith('JXSC_') and not k.upper().endswith('_PROVIDER_MODE')}
    with socket.socket() as available:
        available.bind(('127.0.0.1', 0))
        port = available.getsockname()[1]
    app_env['SPRING_APPLICATION_JSON'] = json.dumps({
        'spring.profiles.active': 'test',
        'spring.config.location': 'classpath:/application.yml,classpath:/application-test.yml',
        'jxsc.tasks.enabled': False,
        'server.address': '127.0.0.1', 'server.port': port,
    })
    java = find_java(args.java_home, app_env)
    settings = subprocess.run([str(java), '-XshowSettings:properties', '-version'],
                              capture_output=True, text=True, encoding='utf-8', errors='replace', env=app_env, creationflags=FLAGS, check=True)
    home = re.search(r'^\s*java.home = (.+)$', (settings.stderr or '') + (settings.stdout or ''), re.M)
    if home:
        actual = Path(home.group(1).strip()) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        if actual.is_file():
            java = actual.resolve()
        else:
            raise RuntimeError('Cannot identify the actual JVM executable for owned-process cleanup')
    else:
        raise RuntimeError('Cannot resolve java.home; refusing to launch through a potentially detached shim')
    with (args.output / 'isolated-server.log').open('wb') as log:
        proc = subprocess.Popen([str(java), '-jar', str(jars[0])], env=app_env,
                                cwd=ROOT / 'backend', stdout=log, stderr=subprocess.STDOUT, creationflags=FLAGS)
        try:
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                if proc.poll() is not None:
                    raise RuntimeError('Isolated server exited; inspect isolated-server.log')
                try:
                    if http(port, '/api/v1/health')[0] == 200:
                        break
                except OSError:
                    pass
                time.sleep(.5)
            else:
                raise RuntimeError('Isolated server readiness timed out')
            status, login = http(port, '/api/v1/auth/dev-login', body={'fixture': 'buyer'})
            if status != 200:
                raise RuntimeError('Isolated DEV login failed')
            token = login['data']['accessToken']
            schema = env['MYSQL_TEST_URL'].split('/')[-1].split('?')[0]
            statements = [f'USE {schema};',
                          "INSERT INTO user_account(nickname,status,created_at,updated_at) VALUES ('DEV FC01 isolated','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));",
                          'SET @u=LAST_INSERT_ID();',
                          "UPDATE user_wechat_identity SET user_id=@u WHERE app_id='DEV' AND openid='buyer';",
                          'INSERT INTO entitlement_subject(user_id) VALUES (@u);']
            for index, group in enumerate(['IN_PROGRESS'] * 12 + ['COMPLETED'] * 2 + ['EXPIRED', 'REVOKED', 'ZERO'], 1):
                title = f"验收样本·{dict(IN_PROGRESS='在学',COMPLETED='已完成',EXPIRED='已过期',REVOKED='已撤销',ZERO='零章节')[group]}·{index:02}"
                title_sql = "CONVERT(X'" + title.encode().hex() + "' USING utf8mb4)"
                source = f'DEV-fc01-{index}'
                statements += [
                    f"INSERT INTO product(type,title,price_fen,status,detail_json,sort_no,created_at,updated_at) VALUES ('COURSE',{title_sql},100,'ON_SALE','[]',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));",
                    'SET @p=LAST_INSERT_ID();',
                    "INSERT INTO course(product_id,validity_type,created_at,updated_at) VALUES (@p,'PERMANENT',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));",
                    'SET @c=LAST_INSERT_ID();',
                    'INSERT INTO entitlement_course_target(course_id) VALUES (@c);',
                    f"INSERT INTO entitlement(user_id,source_key,entitlement_type,target_id,paid_at,starts_at,expires_at) VALUES (@u,'{source}','COURSE_VIEW',@c,UTC_TIMESTAMP(6)-INTERVAL 1 DAY,UTC_TIMESTAMP(6)-INTERVAL 1 DAY,{'UTC_TIMESTAMP(6)-INTERVAL 1 HOUR' if group == 'EXPIRED' else 'NULL'});",
                ]
                if group != 'ZERO':
                    statements += [
                        "INSERT INTO course_chapter(course_id,title,sort_no,duration_seconds,is_trial,enabled,created_at,updated_at) VALUES (@c,'DEV chapter',0,120,false,true,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));",
                        'SET @ch=LAST_INSERT_ID();',
                    ]
                    if group == 'COMPLETED':
                        statements += ['INSERT INTO learning_progress(user_id,chapter_id,position_seconds,completed,last_learned_at) VALUES (@u,@ch,120,true,UTC_TIMESTAMP(6));']
                if group == 'REVOKED':
                    statements += [f"INSERT INTO entitlement_event(user_id,source_key,event_key,event_type,detail_json) VALUES (@u,'{source}','DEV-fc01-revoked','REVOKE','{{}}');"]
            subprocess.run(client, input=isolated.guarded_sql(directory, '\n'.join(statements)), env=client_env,
                           text=True, encoding='utf-8', capture_output=True, check=True, creationflags=FLAGS)
            status, login = http(port, '/api/v1/auth/dev-login', body={'fixture': 'buyer'})
            assert status == 200
            token = login['data']['accessToken']
            checks = {}
            for group, total in [('IN_PROGRESS', 13), ('COMPLETED', 2), ('EXPIRED', 1)]:
                for page in [1, 2]:
                    status, body = http(port, f'/api/v1/learning/courses?status={group}&page={page}&pageSize=10', token)
                    assert status == 200 and body['data']['total'] == total, (status, group, page)
                    expected = max(0, min(10, total - (page - 1) * 10))
                    assert len(body['data']['items']) == expected, (group, page)
                    checks[f'{group}-{page}'] = body
            status, body = http(port, '/api/v1/learning/courses?status=INVALID', token)
            assert status == 400 and body['code'] == 'VALIDATION_ERROR'
            checks['invalid'] = body
            status, legacy = http(port, '/api/v1/learning/courses', token)
            assert status == 200 and legacy['data']['total'] == 17
            checks['legacy'] = legacy
            status, api = http(port, '/v3/api-docs')
            assert status == 200
            parameters = api['paths']['/api/v1/learning/courses']['get']['parameters']
            param = next(p for p in parameters if p['name'] == 'status')
            assert set(param['schema']['enum']) == {'IN_PROGRESS', 'COMPLETED', 'EXPIRED'} and not param['required']
            checks['openapi-status'] = param
            (args.output / 'http-evidence.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
            if args.wechat_script:
                with tempfile.TemporaryDirectory(prefix='jxsc-fc01-session-') as tmp:
                    session_path = Path(tmp) / 'session.json'
                    session_path.write_text(json.dumps({'port': port, 'token': token}), encoding='utf-8')
                    node_env = os.environ.copy()
                    node_env['JXSC_FC01_PRIVATE_SESSION'] = str(session_path)
                    for key in list(node_env):
                        if re.match(r'^(https?|all)_proxy$', key, re.I):
                            del node_env[key]
                    node_env['NO_PROXY'] = 'localhost,127.0.0.1'
                    result = subprocess.run(['node', str(args.wechat_script.resolve())], env=node_env,
                                            cwd=args.wechat_script.resolve().parent, creationflags=FLAGS, timeout=240,
                                            capture_output=True, text=True, encoding='utf-8', errors='replace')
                    output = (result.stdout or '') + (result.stderr or '')
                    (args.output / 'wechat-automation.log').write_text(output, encoding='utf-8')
                    print(output, flush=True)
                    if result.returncode:
                        raise RuntimeError('WeChat verification failed; inspect wechat-automation.log')
            report = {'status': 'PASS', 'database': 'owned-disposable', 'port': port, 'tasksEnabled': False,
                      'customerDataChanged': False, 'httpChecks': len(checks), 'wechatExecuted': bool(args.wechat_script)}
            (args.output / 'smoke-report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
            print(json.dumps(report))
            return 0
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=10)


if __name__ == '__main__':
    p = isolated.parser(__doc__)
    p.add_argument('--mysql-bin')
    p.add_argument('--output', type=Path, default=ROOT / 'target/smoke/2026-10-13-fc01')
    p.add_argument('--wechat-script', type=Path)
    args = p.parse_args()
    args.goals = ['-DskipTests', 'package']
    raise SystemExit(isolated.run(args, functools.partial(smoke, args)))
