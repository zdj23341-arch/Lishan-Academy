"""Safety checks at filesystem and subprocess boundaries; no MySQL service touched."""
import os
from pathlib import Path
import subprocess
import sys
import io
from contextlib import redirect_stdout
import tempfile
import unittest
from unittest.mock import patch

import maven
import with_local_mysql as mysql


class MavenRunnerTest(unittest.TestCase):
    def test_child_output_and_failure_exit_code_are_visible(self):
        output = io.StringIO()
        with redirect_stdout(output):
            code = maven.run_command([sys.executable, '-c', 'print("child evidence"); raise SystemExit(7)'])
        self.assertEqual(7, code)
        self.assertIn('child evidence', output.getvalue())

    def test_discovers_distribution_in_wrapper_cache_without_mvn_on_path(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            distribution = home / '.m2/wrapper/dists/apache-maven-bin/hash/apache-maven'
            (distribution / 'boot').mkdir(parents=True)
            (distribution / 'bin').mkdir()
            (distribution / 'boot/plexus-classworlds-2.9.0.jar').touch()
            (distribution / 'bin/m2.conf').touch()
            with patch.object(Path, 'home', return_value=home), patch.dict(os.environ, {}, clear=True), patch('shutil.which', return_value=None):
                self.assertEqual(distribution.resolve(), maven.find_maven())

    def test_arguments_with_shell_characters_remain_single_arguments(self):
        with tempfile.TemporaryDirectory(prefix='maven space ') as directory:
            distribution = Path(directory)
            (distribution / 'boot').mkdir()
            (distribution / 'bin').mkdir()
            launcher = distribution / 'boot/plexus-classworlds-2.9.0.jar'
            launcher.touch()
            (distribution / 'bin/m2.conf').touch()
            command = maven.maven_command(Path('java'), distribution, Path('backend space'), ['-Dvalue=a&b', 'test'])
            self.assertIn(str(launcher), command)
            self.assertEqual(['-Dvalue=a&b', 'test'], command[-2:])
            self.assertNotIn('cmd.exe', command)


class MySqlRunnerSafetyTest(unittest.TestCase):
    def test_inherited_spring_database_and_jvm_configuration_is_not_forwarded(self):
        dangerous = {
            'SPRING_FLYWAY_URL': 'jdbc:mysql://127.0.0.1:0/poison',
            'SPRING_FLYWAY_USER': 'poison',
            'SPRING_FLYWAY_PASSWORD': 'poison',
            'SPRING_FLYWAY_SCHEMAS_0': 'poison',
            'SPRING_FLYWAY_DEFAULT_SCHEMA': 'poison',
            'SPRING_FLYWAY_CREATE_SCHEMAS': 'true',
            'SPRING_DATASOURCE_HIKARI_JDBC_URL': 'jdbc:mysql://127.0.0.1:0/poison',
            'SPRING_APPLICATION_JSON': '{"spring":{"flyway":{"url":"poison"}}}',
            'SPRING_CONFIG_ADDITIONAL_LOCATION': 'file:poison.yml',
            'DB_URL': 'jdbc:mysql://127.0.0.1:0/poison',
            'MYSQL_TEST_URL': 'jdbc:mysql://127.0.0.1:0/poison',
            'JAVA_TOOL_OPTIONS': '-Dspring.flyway.url=poison',
            'JDK_JAVA_OPTIONS': '-Dspring.flyway.url=poison',
            '_JAVA_OPTIONS': '-Dspring.flyway.url=poison',
            'MAVEN_OPTS': '-Dspring.flyway.url=poison',
            'MAVEN_ARGS': '-Dspring.flyway.url=poison',
        }
        source = {'PATH': 'tool-path', 'JAVA_HOME': 'jdk-home', **dangerous}
        result = mysql.isolated_environment(source)
        self.assertEqual({'PATH': 'tool-path', 'JAVA_HOME': 'jdk-home'}, result)
        self.assertEqual('poison', source['SPRING_FLYWAY_PASSWORD'])

    def test_rejects_cleanup_outside_exact_temporary_parent(self):
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory).resolve()
            with self.assertRaisesRegex(RuntimeError, 'ownership'):
                mysql.validate_owned_runtime(parent.parent / 'jxsc-mysql-test-unowned', parent)
            with self.assertRaisesRegex(RuntimeError, 'ownership'):
                mysql.validate_owned_runtime(parent / 'other-project', parent)
            mysql.validate_owned_runtime(parent / 'jxsc-mysql-test-owned', parent)

    def test_rejects_connection_to_unowned_mysql_directory(self):
        result = subprocess.CompletedProcess([], 0, 'C:/some-existing-service/data/\n', '')
        with patch('subprocess.run', return_value=result):
            with self.assertRaisesRegex(RuntimeError, 'does not belong'):
                mysql.owned_data_directory(['mysql'], {}, Path('owned/data'), 0)

    def test_administrative_sql_contains_same_connection_fail_closed_guard(self):
        sql = mysql.guarded_sql("D:/temporary/it's/data/", 'CREATE DATABASE owned;')
        self.assertIn('PREPARE', sql)
        self.assertLess(sql.index('PREPARE'), sql.index('CREATE DATABASE'))
        self.assertIn('CAST(@@datadir AS BINARY)', sql)
        self.assertIn('JXSC_REFUSE_UNOWNED_MYSQL', sql)
        self.assertNotIn("it's", sql)


if __name__ == '__main__':
    unittest.main()
