"""Restore into a newly spawned loopback MySQL only; never accept a target host/database.

With no --backup, exercise migration, logical backup, isolated restore and row/trigger checks.
With --backup, verify its checksum then restore it into the same disposable isolation boundary.
Outputs may contain protected object identifiers: store the report with backup access controls.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
from urllib.parse import urlsplit
import with_local_mysql as isolated
from maven import FLAGS, run_command
from backup_database import backup


def sql(client, env, directory, text):
    result=subprocess.run(client,input=isolated.guarded_sql(directory,text),env=env,
                          capture_output=True,text=True,encoding='utf-8',creationflags=FLAGS,timeout=180)
    if result.returncode:
        raise RuntimeError('Isolated restore SQL failed: '+result.stderr[:1000])
    return result.stdout.splitlines()[1:]  # directory guard SELECT 1


def drill(args, build, env, client, client_env, directory):
    args.output.mkdir(parents=True,exist_ok=True)
    source=urlsplit(env['MYSQL_TEST_URL'][5:]).path[1:]
    if args.backup is None:
        code=run_command(build,env)
        if code:return code
        schemas=sql(client,client_env,directory,"SELECT table_schema FROM information_schema.tables WHERE table_name='flyway_schema_history' AND table_schema LIKE 'jxsc_test_%';")
        if len(schemas)!=1:raise RuntimeError('Expected one freshly migrated source schema')
        source=schemas[0]
        # Explicit synthetic financial-history sample for restore integrity; no provider or money action.
        sql(client,client_env,directory,f"INSERT INTO {source}.commission_account_ledger(distributor_id,business_key,event_type,pending_delta,available_delta,withdrawing_delta,paid_out_delta,debt_delta,before_json,after_json,created_at) SELECT distributor_id,'RESTORE_SYNTHETIC_PAID_OUT','RESTORE_FIXTURE',0,0,0,paid_out_fen,0,JSON_OBJECT('paidOutFen',0),JSON_OBJECT('paidOutFen',paid_out_fen),UTC_TIMESTAMP(6) FROM {source}.commission_account WHERE paid_out_fen=100 LIMIT 1;")
        dump=args.output / ('drill-'+secrets.token_hex(6)+'.sql')
        connection=[x for x in client[1:] if x not in ('--batch','--skip-column-names','--raw','--skip-reconnect') and not x.startswith('--connect-timeout')]
        backup(Path(client[0]).parent,source,dump,client_env,connection)
    else:
        dump=args.backup.resolve()
    manifest=json.loads(dump.with_suffix(dump.suffix+'.json').read_text(encoding='utf-8'))
    with dump.open('rb') as stream:
        if hashlib.file_digest(stream,'sha256').hexdigest()!=manifest['sha256']:
            raise RuntimeError('Backup checksum mismatch')
    target='jxsc_restore_'+secrets.token_hex(12)
    sql(client,client_env,directory,f'CREATE DATABASE {target} CHARACTER SET utf8mb4;')
    # Entire import is confined to the owned process and begins with the same-connection guard.
    sql(client,client_env,directory,f'USE {target};\n'+dump.read_text(encoding='utf-8'))
    counts=sql(client,client_env,directory,f"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='{target}'; SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema='{target}'; SELECT version FROM {target}.flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;")
    if len(counts)!=3 or int(counts[0])<50 or int(counts[1])<10:
        raise RuntimeError('Restored schema or immutable triggers missing')
    refs=sql(client,client_env,directory,f"SELECT id,storage_key,sealed_version,bytes,sha256 FROM {target}.file_asset WHERE status='READY' ORDER BY id;")
    (args.output/'protected-object-versions.tsv').write_text('id\tkey\tversion\tbytes\tsha256\n'+'\n'.join(refs)+'\n',encoding='utf-8')
    if args.backup is None:
        tables=sql(client,client_env,directory,f"SELECT table_name FROM information_schema.tables WHERE table_schema='{source}' ORDER BY table_name;")
        for table in tables:
            if not re.fullmatch('[a-z0-9_]+',table):raise RuntimeError('Unexpected table name')
            comparison=sql(client,client_env,directory,f'SELECT (SELECT COUNT(*) FROM {source}.{table})=(SELECT COUNT(*) FROM {target}.{table});')
            if comparison!=['1']:raise RuntimeError('Restored row count mismatch: '+table)
            checksums=sql(client,client_env,directory,f'CHECKSUM TABLE {source}.{table}, {target}.{table} EXTENDED;')
            if len(checksums)!=2 or checksums[0].split('\t')[-1]!=checksums[1].split('\t')[-1]:
                raise RuntimeError('Restored content checksum mismatch: '+table)
        if not refs:raise RuntimeError('Restore fixture must include READY version references')
        before_refs=sql(client,client_env,directory,f"SELECT id,storage_key,sealed_version,bytes,sha256 FROM {source}.file_asset WHERE status='READY' ORDER BY id;")
        if refs!=before_refs:raise RuntimeError('Restored protected version/hash differs')
    immutable=subprocess.run(client,input=isolated.guarded_sql(directory,f"UPDATE {target}.operation_audit_log SET reason='restore corruption test' LIMIT 1;"),env=client_env,
                             capture_output=True,text=True,encoding='utf-8',creationflags=FLAGS,timeout=10)
    if immutable.returncode==0 or 'append-only' not in immutable.stderr:
        raise RuntimeError('Restored audit immutability was not enforced')
    samples={}
    if args.backup is None:
        for table in ('account_closure','retained_personal_data','entitlement','payment_transaction','commission_account_ledger'):
            samples[table]=int(sql(client,client_env,directory,f'SELECT COUNT(*) FROM {target}.{table};')[0])
            if samples[table]==0:raise RuntimeError('Missing populated restore evidence: '+table)
        for table in ('account_closure','entitlement','commission_account_ledger'):
            blocked=subprocess.run(client,input=isolated.guarded_sql(directory,f'UPDATE {target}.{table} SET id=id LIMIT 1;'),env=client_env,capture_output=True,text=True,encoding='utf-8',creationflags=FLAGS,timeout=10)
            if blocked.returncode==0 or not re.search('immutable|append-only',blocked.stderr,re.I):
                raise RuntimeError('Restored immutability not enforced: '+table)
    immutable_count=sql(client,client_env,directory,f"SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema='{target}' AND action_statement LIKE '%SIGNAL SQLSTATE%';")
    report={'status':'PASS','tables':int(counts[0]),'triggers':int(counts[1]),'immutableTriggers':int(immutable_count[0]),'migrationVersion':counts[2],
            'populatedSamples':samples,
            'readyObjectReferences':len(refs),'sourceChecksum':manifest['sha256'],
            'isolation':'Owned disposable loopback MySQL; no existing services/databases used',
            'mode':'generated-fixture' if args.backup is None else 'imported-backup',
            'verification':verification_checks(args.backup is None),
            'limits':'Cloud object bytes and deployment key vault restore require a deployment drill'}
    (args.output/'restore-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps(report));return 0


def verification_checks(generated):
    checks=['Backup SHA256 matches manifest', 'Isolated import succeeded',
            'Restored table/trigger counts and latest migration inspected',
            'READY object references exported from restored database',
            'Restored audit UPDATE rejected']
    if generated:
        checks += ['All source/restored table row counts and EXTENDED checksums compared',
                   'Exact source/restored READY version/size/SHA256 references compared',
                   'Populated closure/retention/grant/payment/ledger samples checked',
                   'Restored closure/grant/financial-ledger UPDATE rejected']
    return checks


if __name__=='__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    p=isolated.parser(__doc__)
    p.add_argument('--mysql-bin')
    p.add_argument('--backup',type=Path)
    p.add_argument('--output',type=Path,default=Path('target/restore-drill'))
    args=p.parse_args()
    args.goals=['-Dtest=AccountClosureIntegrationTest','test']
    raise SystemExit(isolated.run(args,lambda *context:drill(args,*context)))
