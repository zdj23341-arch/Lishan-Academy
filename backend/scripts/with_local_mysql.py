"""Run Maven against a new loopback-only MySQL process; never use an existing service."""
import os
import argparse
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time

from maven import FLAGS, build_command, parser, run_command


def isolated_environment(source):
    # Application configuration and JVM option injection must not escape the owned
    # process. Runtime connection values are generated below, never inherited.
    option_keys = {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'MAVEN_OPTS', 'MAVEN_ARGS'}
    prefixes = ('SPRING_', 'DB_', 'MYSQL_TEST_', 'FLYWAY_')
    return {key: value for key, value in source.items()
            if key.upper().replace('.', '_').replace('-', '_') not in option_keys
            and not key.upper().replace('.', '_').replace('-', '_').startswith(prefixes)}


def validate_owned_runtime(runtime, temp_parent):
    if runtime.resolve() != runtime or runtime.parent != temp_parent or not runtime.name.startswith('jxsc-mysql-test-'):
        raise RuntimeError('Temporary directory ownership changed; refusing cleanup')


def owned_data_directory(client, env, data, flags):
    result = subprocess.run(client, input='SELECT @@datadir;', text=True, encoding='utf-8',
                            capture_output=True, env=env, creationflags=flags, timeout=5)
    if result.returncode:
        return None
    rows = result.stdout.splitlines()
    if len(rows) != 1 or not rows[0] or Path(rows[0]).resolve() != data.resolve():
        raise RuntimeError('Connected MySQL data directory does not belong to this test run')
    return rows[0]


def guarded_sql(data_directory, statements):
    # Same-connection guard closes the readiness/admin race; batch mode stops on failure.
    expected = data_directory.encode('utf-8').hex()
    return (f"SET @jxsc_directory_guard = IF(CAST(@@datadir AS BINARY) = X'{expected}', "
            "'SELECT 1', 'JXSC_REFUSE_UNOWNED_MYSQL');\n"
            "PREPARE jxsc_directory_guard FROM @jxsc_directory_guard;\n"
            "EXECUTE jxsc_directory_guard;\n"
            "DEALLOCATE PREPARE jxsc_directory_guard;\n" + statements)


def run(args, operation=None):
    suffix = '.exe' if os.name == 'nt' else ''
    command = str(Path(args.mysql_bin) / f'mysql{suffix}') if args.mysql_bin else shutil.which('mysql')
    if not command or not Path(command).is_file():
        raise RuntimeError('MySQL client was not found; add it to PATH or pass --mysql-bin')
    mysql = Path(command).resolve()
    mysqld = mysql.with_name(f'mysqld{suffix}')
    if not mysqld.is_file():
        raise RuntimeError('MySQL server must be installed beside the client')
    env = isolated_environment(os.environ)
    build = build_command(args, env)
    temp_parent = Path(tempfile.gettempdir()).resolve()
    runtime = Path(tempfile.mkdtemp(prefix='jxsc-mysql-test-', dir=temp_parent)).resolve()
    validate_owned_runtime(runtime, temp_parent)
    server = None
    client = None
    data = runtime / 'data'
    log = (runtime / 'server.log').open('wb')
    client_env = env.copy()
    client_env.pop('MYSQL_PWD', None)
    client_env['MYSQL_TEST_LOGIN_FILE'] = str(runtime / 'unused-mylogin.cnf')
    try:
        console = ['--console'] if os.name == 'nt' else []
        print('Preparing an isolated MySQL test instance...', flush=True)
        subprocess.run([str(mysqld), '--no-defaults', '--initialize-insecure',
                        f'--basedir={mysql.parent.parent}', f'--datadir={data}', *console],
                       stdout=log, stderr=subprocess.STDOUT, check=True, creationflags=FLAGS, timeout=90)
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            port = listener.getsockname()[1]
        server = subprocess.Popen([str(mysqld), '--no-defaults', f'--basedir={mysql.parent.parent}',
                                   f'--datadir={data}', '--bind-address=127.0.0.1', f'--port={port}',
                                   '--mysqlx=OFF', '--default-time-zone=+00:00',
                                   '--innodb-buffer-pool-size=64M', *console],
                                  stdout=log, stderr=subprocess.STDOUT, creationflags=FLAGS)
        client = [str(mysql), '--no-defaults', '--protocol=TCP', '--host=127.0.0.1', f'--port={port}',
                  '--user=root', '--batch', '--skip-column-names', '--raw', '--skip-reconnect',
                  '--connect-timeout=3', '--default-character-set=utf8mb4']
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if server.poll() is not None:
                raise RuntimeError('Temporary MySQL exited before becoming ready')
            directory = owned_data_directory(client, client_env, data, FLAGS)
            if directory is not None:
                break
            time.sleep(.25)
        else:
            raise RuntimeError('Temporary MySQL did not become ready within 60 seconds')
        password = secrets.token_urlsafe(32)
        schema = 'jxsc_test_' + secrets.token_hex(12)
        setup = f'CREATE DATABASE {schema} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n'
        setup += f"ALTER USER 'root'@'localhost' IDENTIFIED BY '{password}';\n"
        result = subprocess.run(client, input=guarded_sql(directory, setup), text=True, encoding='utf-8',
                                capture_output=True, env=client_env, creationflags=FLAGS, timeout=10)
        if result.returncode:
            raise RuntimeError('Could not initialize the dedicated test database')
        client_env['MYSQL_PWD'] = password
        env.update(MYSQL_TEST_URL=f'jdbc:mysql://127.0.0.1:{port}/{schema}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',
                   MYSQL_TEST_USERNAME='root', MYSQL_TEST_PASSWORD=password, MYSQL_TEST_OWNED_DATADIR=directory)
        print(f'Running Maven against a new database on loopback port {port}.', flush=True)
        return run_command(build, env) if operation is None else operation(build, env, client, client_env, directory)
    except BaseException:
        log.flush()
        print('Temporary MySQL log (last 20 lines):', file=sys.stderr)
        print('\n'.join((runtime / 'server.log').read_text(encoding='utf-8', errors='replace').splitlines()[-20:]), file=sys.stderr)
        raise
    finally:
        if server is not None and server.poll() is None:
            try:
                directory = owned_data_directory(client, client_env, data, FLAGS)
                if directory is None:
                    raise RuntimeError('Could not verify temporary MySQL ownership')
                subprocess.run(client, input=guarded_sql(directory, 'SHUTDOWN;'), text=True,
                               encoding='utf-8', capture_output=True, env=client_env,
                               creationflags=FLAGS, timeout=10, check=True)
            except (RuntimeError, subprocess.SubprocessError, OSError):
                print('SQL shutdown unavailable; stopping only the owned server process.', file=sys.stderr)
                if server.poll() is None:
                    server.terminate()
            try:
                server.wait(timeout=15)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait(timeout=10)
        log.close()
        validate_owned_runtime(runtime, temp_parent)
        shutil.rmtree(runtime)
        print('Temporary MySQL instance stopped and its data removed.', flush=True)


if __name__ == '__main__':
    arguments = parser(__doc__)
    arguments.add_argument('--mysql-bin', help='directory containing mysql and mysqld')
    arguments.add_argument('goals', nargs=argparse.REMAINDER)
    try:
        sys.exit(run(arguments.parse_args()))
    except (RuntimeError, subprocess.SubprocessError, OSError) as error:
        print(f'Test setup failed: {error}', file=sys.stderr)
        sys.exit(1)
