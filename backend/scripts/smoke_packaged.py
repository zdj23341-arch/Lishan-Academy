"""Start the packaged production-recovery profile twice on owned disposable MySQL.

Uses fresh ephemeral signing keys and a loopback ClamAV PING protocol collaborator.
No channel calls, production credentials, existing database or money operations.
Requires Python cryptography and real ffmpeg/ffprobe executables.
"""
import base64
import json
import os
from pathlib import Path
import secrets
import re
import socket
import subprocess
import tempfile
import threading
import time
import urllib.request
import urllib.error
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization
import with_local_mysql as isolated
from maven import FLAGS, find_java


def request(port, path, method='GET'):
    try:
        with urllib.request.urlopen(urllib.request.Request(f'http://127.0.0.1:{port}{path}',method=method), timeout=5) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def smoke(args, build, env, client, client_env, directory):
    jars=list(Path('target').glob('jxsc-backend-*.jar'))
    if len(jars)!=1:raise RuntimeError('Package one backend JAR first')
    args.output.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='jxsc-smoke-') as tmp, socket.socket() as scanner:
        root=Path(tmp)
        key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        (root/'private.pem').write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
        (root/'public.pem').write_bytes(key.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo))
        scanner.bind(('127.0.0.1',0));scanner.listen();scanner.settimeout(.2)
        stop=threading.Event()
        def ping():
            while not stop.is_set():
                try:conn,_=scanner.accept()
                except socket.timeout:continue
                except OSError:return
                with conn:
                    conn.settimeout(2)
                    if conn.recv(6)==b'zPING\0':conn.sendall(b'PONG\0')
        worker=threading.Thread(target=ping,daemon=True);worker.start()
        app_env={k:v for k,v in env.items() if not k.upper().startswith('JXSC_') and not k.upper().endswith('_PROVIDER_MODE')}
        config={
          'spring.profiles.active':'prod','spring.config.import':'configtree:'+root.as_posix()+'/',
          'spring.datasource.url':env['MYSQL_TEST_URL'],'spring.datasource.username':env['MYSQL_TEST_USERNAME'],
          'jxsc.production.recovery-only':'true','jxsc.tasks.enabled':'false',
          'jxsc.wechat.app-id':'wxSmoke','jxsc.wechat.merchant-id':'1900000001',
          'jxsc.wechat.login.app-secret':secrets.token_hex(16),'jxsc.wechat.pay.enabled':'true',
          'jxsc.wechat.pay.merchant-serial':'SMOKE','jxsc.wechat.pay.private-key-file':str(root/'private.pem'),
          'jxsc.wechat.pay.api-v3-key':secrets.token_hex(16),
          'jxsc.wechat.pay.trusted-public-key-files':json.dumps({'PUB_KEY_ID_SMOKE':str(root/'public.pem')}),
          'jxsc.wechat.pay.response-signing-mode':'PUBLIC_KEY','jxsc.wechat.pay.response-key-id':'PUB_KEY_ID_SMOKE',
          'jxsc.wechat.pay.notify-base-url':'https://smoke.invalid',
          'jxsc.storage.cos.endpoint':'https://smoke-1250000000.cos.ap-shanghai.myqcloud.com',
          'jxsc.storage.cos.secret-id':secrets.token_hex(16),'jxsc.storage.cos.secret-key':secrets.token_hex(16),
          'jxsc.scanner.host':'127.0.0.1','jxsc.scanner.port':str(scanner.getsockname()[1]),
          'jxsc.media.ffmpeg':str(args.ffmpeg.resolve()),'jxsc.media.ffprobe':str(args.ffprobe.resolve()),
          'jxsc.logistics.customer':'smoke','jxsc.logistics.key':secrets.token_hex(16),
          'jxsc.crypto.key-base64':base64.b64encode(secrets.token_bytes(32)).decode(),
        }
        # Exercise the compose configtree datasource password override without DB_PASSWORD.
        (root/'spring.datasource.password').write_text(env['MYSQL_TEST_PASSWORD'],encoding='utf-8')
        app_env['SPRING_APPLICATION_JSON']=json.dumps(config)
        # Oracle's Windows PATH launcher can spawn a separate JVM. Launch java.home's
        # executable so terminate/wait own the actual application, not only that shim.
        java=find_java(args.java_home,app_env)
        settings=subprocess.run([str(java),'-XshowSettings:properties','-version'],env=app_env,
            capture_output=True,text=True,errors='replace',creationflags=FLAGS,check=True)
        home=re.search(r'^\s*java.home = (.+)$',settings.stderr,re.M)
        if home:
            actual=Path(home.group(1).strip())/'bin'/('java.exe' if os.name=='nt' else 'java')
            if actual.is_file():java=actual.resolve()
        results=[]
        try:
            for attempt in (1,2):
                with socket.socket() as available:
                    available.bind(('127.0.0.1',0));port=available.getsockname()[1]
                log_path=args.output/f'start-{attempt}.log'
                with log_path.open('wb') as log:
                    proc=subprocess.Popen([str(java),'-jar',str(jars[0]),f'--server.port={port}','--server.address=127.0.0.1'],env=app_env,stdout=log,stderr=subprocess.STDOUT,creationflags=FLAGS)
                    try:
                        deadline=time.monotonic()+100
                        while time.monotonic()<deadline:
                            if proc.poll() is not None:raise RuntimeError(f'Packaged startup failed; inspect {log_path}')
                            try:
                                if request(port,'/api/v1/health/ready')[0]==200:break
                            except OSError:pass
                            time.sleep(.5)
                        else:raise RuntimeError(f'Readiness timed out; inspect {log_path}')
                        checks={}
                        for path,method in [('/api/v1/health','GET'),('/api/v1/health/ready','GET'),('/v3/api-docs','GET'),('/api/v1/auth/dev-login','POST'),('/api/v1/admin/runtime','GET')]:
                            status,_=request(port,path,method);checks[path]=status
                        assert checks['/api/v1/health']==200 and checks['/api/v1/health/ready']==200,checks
                        assert all(checks[p] in (401,403,404) for p in ('/v3/api-docs','/api/v1/auth/dev-login','/api/v1/admin/runtime')),checks
                        results.append(checks)
                    finally:
                        proc.terminate()
                        try:proc.wait(timeout=40)
                        except subprocess.TimeoutExpired:proc.kill();proc.wait(timeout=10)
            text="SELECT COUNT(*),MAX(CAST(version AS UNSIGNED)) FROM "+env['MYSQL_TEST_URL'].split('/')[-1].split('?')[0]+".flyway_schema_history WHERE success=1;"
            check=subprocess.run(client,input=isolated.guarded_sql(directory,text),env=client_env,capture_output=True,text=True,creationflags=FLAGS,check=True)
            assert check.stdout.splitlines()[-1]=='17\t17',check.stdout
            report={'status':'PASS','starts':2,'migrations':17,'checks':results,'limits':'Loopback PING collaborator, ephemeral WeChat keys, recovery-only; no live provider/ClamAV daemon/container acceptance'}
            (args.output/'smoke-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
            print(json.dumps(report));return 0
        finally:stop.set();worker.join(timeout=3)


if __name__=='__main__':
    p=isolated.parser(__doc__)
    p.add_argument('--mysql-bin')
    p.add_argument('--ffmpeg',type=Path,required=True)
    p.add_argument('--ffprobe',type=Path,required=True)
    p.add_argument('--output',type=Path,default=Path('target/packaged-smoke'))
    args=p.parse_args();args.goals=['-DskipTests','package']
    raise SystemExit(isolated.run(args,lambda *context:smoke(args,*context)))
