"""FC-02 anonymous HTTP/optional WeChat verification on a disposable database only.

The configured agreements explicitly contain isolated test text, never legal copy.
The customer server, orders, account/session and published content are untouched.
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

import with_local_mysql as isolated
from maven import FLAGS, find_java, run_command
from learning_filter_smoke import http

ROOT = Path(__file__).resolve().parents[2]


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
    config = {'spring.profiles.active': 'test',
              'spring.config.location': 'classpath:/application.yml,classpath:/application-test.yml',
              'jxsc.tasks.enabled': False, 'server.address': '127.0.0.1', 'server.port': port,
              'jxsc.agreements.terms.article-id': '900001', 'jxsc.agreements.terms.version': 'isolated-v1',
              'jxsc.agreements.privacy.article-id': '900002', 'jxsc.agreements.privacy.version': 'isolated-v1'}
    app_env['SPRING_APPLICATION_JSON'] = json.dumps(config)
    java = find_java(args.java_home, app_env)
    settings = subprocess.run([str(java), '-XshowSettings:properties', '-version'],
                              capture_output=True, text=True, encoding='utf-8', errors='replace',
                              env=app_env, creationflags=FLAGS, check=True)
    home = re.search(r'^\s*java.home = (.+)$', settings.stderr + settings.stdout, re.M)
    actual = Path(home.group(1).strip()) / 'bin' / ('java.exe' if os.name == 'nt' else 'java') if home else None
    if not actual or not actual.is_file():
        raise RuntimeError('Cannot identify actual JVM for owned-process cleanup')
    schema = env['MYSQL_TEST_URL'].split('/')[-1].split('?')[0]

    def sql(statement):
        subprocess.run(client, input=isolated.guarded_sql(directory, f'USE {schema};\n' + statement),
                       env=client_env, text=True, encoding='utf-8', capture_output=True, check=True, creationflags=FLAGS)

    def literal(value):
        return "CONVERT(X'" + value.encode().hex() + "' USING utf8mb4)"

    with (args.output / 'isolated-server.log').open('wb') as log:
        proc = subprocess.Popen([str(actual.resolve()), '-jar', str(jars[0])], env=app_env,
                                cwd=ROOT / 'backend', stdout=log, stderr=subprocess.STDOUT, creationflags=FLAGS)
        try:
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                if proc.poll() is not None:
                    raise RuntimeError('Isolated server exited')
                try:
                    if http(port, '/api/v1/health')[0] == 200:
                        break
                except OSError:
                    pass
                time.sleep(.5)
            else:
                raise RuntimeError('Isolated server readiness timed out')
            checks = {}
            status, missing = http(port, '/api/v1/config/public')
            assert status == 200 and missing['data']['agreementLinks'] == {}
            checks['missing-content'] = missing
            for aid, title in [(900001, '用户协议'), (900002, '隐私政策')]:
                text = f'{title} · 仅隔离测试内容。\n这不是客户确认的正式协议，不可用于上线。\n测试公开阅读、主动同意和返回流程。'
                sql(f"INSERT INTO article(id,title,status,body_json,published_at,created_at,updated_at) VALUES ({aid},"
                    f"{literal(title)},'PUBLISHED',{literal(json.dumps([{'type':'TEXT','text':text}],ensure_ascii=False))},"
                    "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));")
            status, config_response = http(port, '/api/v1/config/public')
            assert status == 200 and len(config_response['data']['agreementLinks']) == 2
            checks['published-configuration'] = config_response
            for kind, link in config_response['data']['agreementLinks'].items():
                status, article = http(port, '/api/v1/articles/' + link['articleId'])
                assert status == 200 and article['data']['contentBlocks'][0]['text']
                checks[kind + '-anonymous-article'] = article
            for status_name in ['DRAFT', 'UNPUBLISHED']:
                sql(f"UPDATE article SET status='{status_name}' WHERE id=900001;")
                status, response = http(port, '/api/v1/config/public')
                assert status == 200 and 'terms' not in response['data']['agreementLinks']
                checks[status_name.lower() + '-excluded'] = response
            sql("UPDATE article SET status='PUBLISHED',body_json='[]' WHERE id=900001;")
            status, response = http(port, '/api/v1/config/public')
            assert status == 200 and 'terms' not in response['data']['agreementLinks']
            checks['empty-excluded'] = response
            sql('UPDATE article SET body_json=' + literal(json.dumps([{'type':'TEXT','text':'用户协议 · 仅隔离测试内容，不可用于上线。'}],ensure_ascii=False)) + ' WHERE id=900001;')
            status, response = http(port, '/api/v1/config/public')
            assert status == 200 and len(response['data']['agreementLinks']) == 2
            checks['restored-readable-content'] = response
            (args.output / 'http-evidence.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2),encoding='utf-8')
            report = {'status':'PASS','database':'owned-disposable','port':port,'customerDataChanged':False,
                      'httpChecks':len(checks),'wechatExecuted':False}
            (args.output / 'http-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
            if args.wechat_script:
                with tempfile.TemporaryDirectory(prefix='jxsc-fc02-runtime-') as tmp:
                    context = Path(tmp)/'context.json'
                    context.write_text(json.dumps({'port':port}),encoding='utf-8')
                    node_env=os.environ.copy();node_env['JXSC_FC02_PRIVATE_CONTEXT']=str(context)
                    for key in list(node_env):
                        if re.match(r'^(https?|all)_proxy$',key,re.I): del node_env[key]
                    node_env['NO_PROXY']='localhost,127.0.0.1'
                    result=subprocess.run(['node',str(args.wechat_script.resolve())],env=node_env,
                        cwd=args.wechat_script.resolve().parent,creationflags=FLAGS,timeout=240,
                        capture_output=True,text=True,encoding='utf-8',errors='replace')
                    output=result.stdout+result.stderr
                    (args.output/'wechat-automation.log').write_text(output,encoding='utf-8');print(output,flush=True)
                    if result.returncode: raise RuntimeError('WeChat verification failed; see saved report/log')
                report['wechatExecuted']=True
            (args.output/'smoke-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
            print(json.dumps(report));return 0
        finally:
            proc.terminate()
            try:proc.wait(timeout=20)
            except subprocess.TimeoutExpired:proc.kill();proc.wait(timeout=10)


if __name__ == '__main__':
    p=isolated.parser(__doc__);p.add_argument('--mysql-bin')
    p.add_argument('--output',type=Path,default=ROOT/'target/smoke/2026-10-13-fc02')
    p.add_argument('--wechat-script',type=Path)
    args=p.parse_args();args.goals=['-DskipTests','package']
    raise SystemExit(isolated.run(args,functools.partial(smoke,args)))
