"""Launch a local Maven distribution through Java, with no command shell."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

FLAGS = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
BACKEND = Path(__file__).resolve().parents[1]


def is_distribution(path):
    return (path / 'bin/m2.conf').is_file() and len(list((path / 'boot').glob('plexus-classworlds-*.jar'))) == 1


def find_maven(configured=None):
    if configured:
        path = Path(configured).resolve()
        path = path.parent.parent if path.is_file() else path
        if is_distribution(path):
            return path
        raise RuntimeError('--maven must identify a Maven distribution directory or bin/mvn command')
    candidates = [Path(os.environ[key]) for key in ('MAVEN_HOME', 'M2_HOME') if os.environ.get(key)]
    command = shutil.which('mvn.cmd' if os.name == 'nt' else 'mvn')
    if command:
        candidates.append(Path(command).resolve().parent.parent)
    candidates.extend(sorted((Path.home() / '.m2/wrapper/dists').glob('**/bin/m2.conf'), reverse=True))
    for root in (Path(os.environ.get('ProgramFiles', 'C:/Program Files')), Path('D:/Program Files')):
        candidates.extend(sorted(root.glob('JetBrains/IntelliJ IDEA*/plugins/maven/lib/maven3/bin/m2.conf'), reverse=True))
    for candidate in candidates:
        candidate = candidate.parent.parent if candidate.is_file() else candidate
        if is_distribution(candidate):
            return candidate.resolve()
    raise RuntimeError('Maven was not found; set MAVEN_HOME or pass --maven')


def find_java(configured=None, env=None):
    env = os.environ if env is None else env
    candidates = [Path(configured) / 'bin/java.exe' if os.name == 'nt' else Path(configured) / 'bin/java'] if configured else []
    if not configured:
        if env.get('JAVA_HOME'):
            candidates.append(Path(env['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java'))
        if shutil.which('java'):
            candidates.append(Path(shutil.which('java')))
    for candidate in candidates:
        if candidate.is_file():
            result = subprocess.run([str(candidate), '-version'], capture_output=True, text=True, creationflags=FLAGS, env=env)
            if result.returncode == 0 and re.search(r'(?:version ")?21[.\s"]', result.stdout + result.stderr):
                return candidate.resolve()
    raise RuntimeError('JDK 21 was not found; set JAVA_HOME or pass --java-home')


def maven_command(java, maven_home, backend, goals):
    if not is_distribution(maven_home):
        raise RuntimeError('A complete Maven distribution is required')
    launcher = next((maven_home / 'boot').glob('plexus-classworlds-*.jar'))
    return [str(java), '-Dfile.encoding=UTF-8', f'-Dmaven.home={maven_home}',
            f'-Dclassworlds.conf={maven_home / "bin/m2.conf"}',
            f'-Dmaven.multiModuleProjectDirectory={backend}', '-classpath', str(launcher),
            'org.codehaus.plexus.classworlds.launcher.Launcher', '-B', '-ntp', *goals]


def parser(description=__doc__):
    result = argparse.ArgumentParser(description=description)
    result.add_argument('--maven', help='Maven distribution directory or bin/mvn path')
    result.add_argument('--java-home', help='JDK 21 directory')
    return result


def build_command(args, env=None):
    goals = args.goals[1:] if args.goals[:1] == ['--'] else args.goals
    return maven_command(find_java(args.java_home, env), find_maven(args.maven), BACKEND, goals or ['test'])


def run_command(command, env=None):
    with subprocess.Popen(command, cwd=BACKEND, env=env, creationflags=FLAGS,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          text=True, encoding='utf-8', errors='replace') as process:
        for line in process.stdout:
            print(line, end='', flush=True)
        return process.wait()


if __name__ == '__main__':
    arguments = parser()
    arguments.add_argument('goals', nargs=argparse.REMAINDER)
    try:
        sys.exit(run_command(build_command(arguments.parse_args())))
    except (RuntimeError, subprocess.SubprocessError, OSError) as error:
        print(f'Maven setup failed: {error}', file=sys.stderr)
        sys.exit(1)
