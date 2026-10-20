# Backend production deployment and recovery

Current development and build instructions: [backend README](../README.md).
The existing server runs its own compose.dev.yml with identity REAL and development seeds disabled.
Payment merchant configuration and live payment acceptance remain incomplete. The production compose
example below is a separate template; do not overwrite the running server configuration with it.
Configuration presence does not establish provider approval or live acceptance.

## Build and launch

Use Java 21, Maven, MySQL 8.0.44, ClamAV with current signatures and FFmpeg/ffprobe with H264/AAC/WEBP
decoding. Backend tests use an isolated native MySQL process, never an existing service:

```text
python scripts/with_local_mysql.py -- test
python scripts/maven.py -- -DskipTests package
```

On Windows use `set PYTHONUTF8=1` before commands. The runner accepts `--java-home`, `--maven` and
`--mysql-bin` before `--`. Maven output and all state belong to the backend. The Dockerfile packages
the same Java application; tests must pass before an image is released. Its distro FFmpeg build must
be verified separately from the Windows 9.0.2 build tested locally.

Copy `.env.example` and `compose.example.yml` to a controlled deployment checkout, fill the approved
nonsecret configuration, and provision the secret directories outside Git. `docker compose -f
compose.example.yml build` and `up -d` are deployment commands; they have not been run here.
Only loopback port 8080 is published. Terminate TLS in a trusted reverse proxy and restrict inbound
traffic to that proxy. Do not expose MySQL or unauthenticated ClamAV INSTREAM to the Internet.
Forwarded headers are ignored by default; configure a specific trusted-proxy boundary before using them.

The application uses `/run/secrets` as a Spring config tree. Required filenames include:

| Application secret file | Content |
|---|---|
| `spring.datasource.password` | Application database password |
| `jxsc.crypto.key-base64` | Base64 of exactly 32 random bytes, retained for old v1 ciphertext |
| `jxsc.wechat.login.app-secret` | AppSecret |
| `jxsc.wechat.pay.api-v3-key` | API v3 32-byte key |
| `jxsc.storage.cos.secret-id`, `jxsc.storage.cos.secret-key` | Limited COS identity and secret |
| `jxsc.logistics.key` | Kuaidi100 query key |
| `merchant-private.pem` and pinned response PEM files | Merchant private key and authenticated provider trust anchors |
| `jxsc.crypto.keyring-json` (optional) | JSON mapping retained key IDs to Base64 32-byte keys |

Set `JXSC_WECHAT_PAY_TRUSTED_PUBLIC_KEY_FILES` to a JSON object mapping authenticated key IDs to
the mounted PEM paths. PUBLIC_KEY mode requires the matching active response/encryption ID.
Keep DB root credentials in the separate `MYSQL_SECRET_DIRECTORY`, containing `mysql-root-password`
and the matching `spring.datasource.password`. Do not mount that directory into the application.
Restrict host access and grant application UID 10001 read access only to its needed files.

MySQL schemas/accounts for dev/test/prod must be distinct. Flyway applies append-only migrations from src/main/resources/db/migration;
Hibernate validates mappings. For a hardened deployment run migrations with a separate controlled
DDL account, then run the app with the minimum runtime privileges; never grant application users
DROP/TRIGGER or disable immutable-history triggers. Back up before migration and use forward fixes.
Compose's initial all-in-one MySQL account is an example requiring that privilege split before release.

Shared migrations are immutable. V22/V23 historical checksums and the V25 forward correction are
explained in [migration compatibility](migration-checksum-recovery-2026-10-17.md). Never run repair
or disable checksum validation merely to bypass a mismatch; inspect applied history first.

Formal referral image generation uses the configured AppID/AppSecret, WeChat quotas and a valid
released page, with private versioned storage. Verify actual scanning and cache expiry on devices.

Initialize the first administrator with one-time `ADMIN_BOOTSTRAP_LOGIN` and
`ADMIN_BOOTSTRAP_PASSWORD` environment secrets, then remove them. The account must change its
password before normal privileged use. No password or sample commercial data is seeded into prod.

## Production admission and service prerequisites

Normal `prod` refuses DEV modes, mixed dev/test profiles, seed fixtures, enabled API docs, missing
database/key/callback/storage/scanner/media/logistics properties and incomplete required create
capabilities. All providers remain REAL. Enable book payment, the approved course/membership payment
path, transfer scene/reports/limits and statements only after actual merchant approval. Configuration
is a precondition, not proof of legal/product eligibility or live acceptance. Run the merchant
and device checks before accepting ordinary transactions.

`JXSC_PRODUCTION_RECOVERY_ONLY=true` is an explicit incident/recovery operating mode. Book/digital
create gates and transfer creation must be disabled; authenticated original-number queries,
callbacks and reconciliation keys remain configured. It permits recovery without inventing success
or new merchant numbers. It is not a sale/withdrawal-ready release and must never satisfy a release
approval checklist. Local packaged smoke exercised this mode only.

COS must use a private versioned bucket at its trusted regional COS HTTPS endpoint. Limit credentials
to required object operations and prefixes; protect referenced versions from lifecycle removal.
Configure browser CORS for only the application's origin, signed PUT and declared headers. Public
covers are served through the public-image relation; paid assets use a server-authorized stream.
Reverse-proxy access logs must omit capability tokens/query signatures. Back up object versions
independently of MySQL. Validate CORS, versioning, post-verification overwrite, HEAD/range, failure,
and real-device playback with the deployed bucket before acceptance.

ClamAV INSTREAM must accept the documented upload bound and report infected/size/unknown failures.
Use `docs/clamd.conf.example`, update signatures, size memory for the real corpus, and monitor daemon
readiness. Set `SCANNER_SPOOL_DIRECTORY` to a dedicated disk-backed host directory (never tmpfs).
Before starting the app, provision it with owner UID/GID 10001, mode 0700, and an 8GiB filesystem
quota; keep at least 5GiB free for two concurrent 2GiB inputs plus margin. Mount only that directory
at `/var/lib/jxsc/scanner`; do not share it between app replicas. Each input is bounded and deleted
in `finally`, including validation/provider failures. A crash can leave `jxsc-verify-*.upload` files:
with the app stopped and all scanner/FFmpeg processes gone, remove only those files inside this
verified spool directory before restart. Monitor quota/free space; insufficient space is retryable.

The example app has a 4GiB memory limit, a fixed 1536MiB JVM heap, a separate 128MiB `/tmp` tmpfs,
and approximately 2.4GiB remaining for JVM non-heap, two native decoder processes, and reclaimable
disk page cache. ClamAV has its own 2GiB container limit. `-max_alloc` bounds a single FFmpeg
allocation, not its total RSS. The host must provide at least 8GiB RAM plus MySQL capacity and the
disk quota. This is a static resource budget, not a measured load acceptance: test two maximum
uploads, native peak RSS/page cache, quota exhaustion, process-crash cleanup, and lease deadlines
on the actual container host. No Docker/container load acceptance was available locally. Actual scan+decode time must fit the
15-minute lease; timeouts retain a retryable non-READY asset. Large legitimate videos may need revised
deployment limits after measured acceptance. Protect the scanner network; it is not a public API.

Kuaidi100 customer/key and approved carrier codes are required. Shunfeng/shunfengkuaiyun/zhongtong
need the frozen recipient phone. Test the actual account's carrier availability and track retention.
The application enforces a minimum 30-minute query interval per shipment and keeps tracks on outage.

## Monitoring, interruption and replay

`GET /api/v1/health` is liveness; `/api/v1/health/ready` is 200/503 with only UP/OUT_OF_SERVICE. Production
readiness checks DB, cached bounded scanner PING and transfer recovery configuration. It does not
perform charge/query requests or prove all cloud services healthy. `FINANCE_OPERATE` can read
`/api/v1/admin/runtime` for task status/retries, expired file leases, order/account holds and distinct
transfer-create/recovery readiness. Use existing order/withdrawal/reconciliation views for safe
last-error and 30-minute UNKNOWN warnings; never expose raw encrypted provider exchanges in logs.

Stop routing new requests, let Spring's 30-second graceful shutdown finish (compose allows 45s),
then restart. Outbox leases are token-fenced and expired leases are reclaimable; do not manually
delete tasks, callbacks, audit or financial history. Retry original-number withdrawal/query endpoints.
Expired asset verification leases are retried by the owner completion endpoint. A local DEV upload
interrupted after file write but before publication may require a new upload ticket; this is not the
production direct-upload path. Never change a held transaction to success in SQL.

Use the audited correction endpoints only when original-number authenticated evidence is complete.
Order correction waits for the whole dispatched refund set; UNKNOWN/conflicts keep the hold.
Withdrawal correction leaves newer in-flight money frozen. Review excess cash loss/debt and permanent
actor/reason/requestId receipts. Administrative risk toggles cannot clear unresolved financial holds.

## Account closure and financial retention

Set `JXSC_PRIVACY_RETENTION_DAYS` from the approved retention policy (1–36500 days). The default
0 deliberately blocks closure with financial history; it is not a legal retention duration. There
is no automatic financial-record deletion job. Closure retains encrypted fiscal identity/address/
aftersale snapshots separately, redacts ordinary views, and requires distinct permission plus an
audited reason to access the retained payload before its deadline. Expired payloads are not exposed.
Operational approval of retention, backup retention and eventual legal disposal remains required.
See the development OpenAPI and AccountClosureController for forfeiture and outstanding obligations.

## Backup, restore and keys

Use a dedicated read-only backup identity in a protected MySQL defaults file. Quiesce application
writes for a coordinated database/object-reference snapshot; take daily backups and before changes.

```text
python scripts/backup_database.py --mysql-bin /path/to/mysql/bin --defaults-file /protected/backup.cnf --database jxsc_prod --output /protected/backups/jxsc-YYYYMMDD.sql
python scripts/restore_drill.py --backup /protected/backups/jxsc-YYYYMMDD.sql --output /protected/restore-report
```

Backup uses single-transaction logical dump, triggers/routines/events and SHA256 manifest; a partial
dump gets no valid manifest. Both SQL and report contain sensitive data. Encrypt backups, restrict
access, store off-host and define retention/recovery objectives. `restore_drill.py` never accepts a
target host; it starts its own loopback MySQL, verifies datadir ownership, imports into a random schema
and deletes only its owned runtime. Never feed it an untrusted SQL dump. No-backup mode generates a
populated fixture and verifies every table row count/content checksum, exact READY key/version/size/hash,
the latest migrations and immutable audit/closure/grant/financial-ledger rejection. Supplied-backup mode validates the checksum/schema/trigger and
extracts references but cannot compare against the original source contents.

A deployment drill must restore each referenced version and verify its size/SHA256, restore the matching keys,
replay only original-number tasks, and reconcile authenticated channel statements before traffic.

Data encryption v1 continues to read the legacy 32-byte key. To rotate, provision an additional
keyring ID, keep all old keys, set `JXSC_CRYPTO_ACTIVE_KEY_ID`; new ciphertext is `v2:<id>:...`.
Do not rewrite immutable evidence. Roll back by retaining every key that has ever encrypted retained
data; test decrypting a backup before retiring anything. Rotate provider response trust with an
authenticated old+new overlap and then select the new active ID. Rotate merchant/COS/scanner access
credentials under the provider's process; configuration references alone do not validate new keys.

## Reproducible packaged smoke

After packaging, with Python `cryptography`, native MySQL and real decoder paths:

```text
python scripts/smoke_packaged.py --ffmpeg /absolute/ffmpeg --ffprobe /absolute/ffprobe
```

It uses ephemeral RSA/data keys, a loopback PING collaborator and owned MySQL; starts the actual JAR
twice and verifies readiness/security. Compare migration history with the checked-out source;
the smoke report migration count is historical and is not a current schema inventory. It proves configtree password
binding and local production-recovery wiring, not live WeChat/scanner/COS/logistics or a Docker image.

## Updating the existing server

Run compose commands in /srv/jxsc/backend with the server-owned compose.dev.yml. Keep the current
secret, runtime and scanner mounts. A normal code update replaces only the app artifact/image and
restarts app; admin updates replace its static build. Do not reimport SQL, replace uploaded media,
reenable development seeds or synchronize local database changes during a routine release.

The database stores media references; local storage bytes and matching encryption keys are separate
runtime state. Preserve them across container replacement. After updates check health, one public
image, login and a representative authenticated API. Keep database/object/key recovery material
outside the repository.
