"""Read-only MySQL logical backup. Defaults file holds credentials; no secrets in process arguments.

Use a write-quiesced application for coordinated database/COS reference snapshots.
Restore only with restore_drill.py into an owned disposable MySQL process.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from maven import FLAGS


def database_name(value):
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{0,63}', value):
        raise ValueError('Invalid database name')
    return value


def backup(mysql_bin, schema, output, env, connection):
    database_name(schema)
    output = Path(output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    dump = Path(mysql_bin) / ('mysqldump.exe' if os.name == 'nt' else 'mysqldump')
    command = [str(dump), *connection, '--single-transaction', '--skip-lock-tables',
               '--hex-blob', '--routines', '--triggers', '--events', '--no-tablespaces',
               '--set-gtid-purged=OFF', '--default-character-set=utf8mb4', schema]
    # CREATE_NEW protects previous backups; partial dumps are never published as valid manifests.
    with output.open('xb') as stream:
        result = subprocess.run(command, env=env, stdout=stream, stderr=subprocess.PIPE,
                                creationflags=FLAGS, timeout=600)
    if result.returncode:
        raise RuntimeError('Database backup failed; inspect credentials/connectivity (partial file retained)')
    with output.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    manifest = output.with_suffix(output.suffix + '.json')
    manifest.write_text(json.dumps({'format': 1, 'sha256': digest, 'database': schema,
                                   'bytes': output.stat().st_size,
                                   'containsSecrets': True,
                                   'objectVersions': 'Extract from restored snapshot; preserve COS versions independently'}, indent=2), encoding='utf-8')
    return manifest


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--mysql-bin', required=True)
    p.add_argument('--defaults-file', type=Path, required=True)
    p.add_argument('--database', required=True)
    p.add_argument('--output', type=Path, required=True)
    a=p.parse_args()
    if not a.defaults_file.is_file():
        p.error('A protected MySQL defaults file is required')
    manifest=backup(a.mysql_bin,a.database,a.output,os.environ.copy(),[f'--defaults-extra-file={a.defaults_file.resolve()}'])
    print('Backup and checksum manifest written:', manifest)


if __name__=='__main__':
    main()
