# DB backup runbook (TD-6)

> Sprint 9-DS-r4. Backlog TD-6 in `docs/BACKLOG-2026-05-21.md`.
>
> Companion to `OPS-SECRETS-RUNBOOK.md`. Documents the backup,
> restore, and disaster-recovery story for the Postgres database
> that backs every service in the stack.

---

## What we're protecting

Single Postgres 16 database `dlmm` on a named Docker volume
(`docker/docker-compose.yml` → `postgres-data`). Schema covers ~22
tables across user / token / pool / liquidity / transaction / fee /
notification / outbox / spasibo / OHLCV domains. Seed scripts
(`init-db.sql`, `02-extended-assets.sql`, `03-seed-trading-history.sql`,
`04-spasibo-seed.sql`) recreate dev state on a fresh volume in ~5
sec but cannot recreate production transaction history.

**Blast radius if lost:**
- All user balances → cannot reconstruct from Kafka (outbox is
  retention-limited to 7 days, see P0-5).
- All open LP positions → users lose principal until manual
  restoration from on-chain settlements (which we don't have yet —
  see below).
- All swap history → analytics/compliance reporting blocked.

This makes the database the **single most critical piece of state
in the platform**. No backup story today = no production deploy.

---

## Sprint 9-DS-r4 deliverable: dev/staging snapshot script

`docker/scripts/pg-snapshot.sh` — wraps `pg_dump` with sensible
defaults, writes timestamped `.dump` files into `docker/backups/`
(gitignored). Verified locally; output:

```text
$ docker/scripts/pg-snapshot.sh
[pg-snapshot] starting at 2026-05-21T19:24:31Z
[pg-snapshot] dump format=custom, target=docker/backups/dlmm-2026-05-21T192431Z.dump
[pg-snapshot] dump complete (4.2 MB)
[pg-snapshot] pruned 0 dumps older than 14 days
```

Designed to be `cron`-runnable on a host outside the compose stack
(jump-box / SRE node). For ops, the cron line is:

```cron
17 3 * * *  /opt/dlmm/docker/scripts/pg-snapshot.sh >> /var/log/dlmm-snapshot.log 2>&1
```

3:17 AM matches the same off-peak window we use for the outbox
cleanup job (`OutboxDispatcher.cleanup`) so the cron-window
contention is intentional and predictable.

## Restore drill (dev) — manual

```bash
# Trash the running DB (in dev)
docker-compose down -v postgres

# Bring up a fresh empty postgres
docker-compose up -d postgres

# Restore from latest snapshot
docker exec -i dlmm-postgres pg_restore -U dlmm -d dlmm --clean --if-exists \
    < docker/backups/dlmm-2026-05-21T192431Z.dump
```

**Verification:** spin up `dlmm-pool-engine` and hit
`/actuator/health`. If JPA `ddl-auto: validate` accepts the
restored schema and a `/api/v1/pools` call returns the expected
row count, the restore is healthy.

## Automated DR drill (Sprint 11 G-35)

`docker/scripts/dr-drill.sh` runs the full snapshot → kill → restore →
verify cycle and reports RTO + per-table row-count parity. Driven by
Dmitry + АВ feedback ("тестируйте failover до того как мы подключаемся").

```bash
# Full drill (DESTRUCTIVE — wipes named volume, simulates total DB
# loss + cold restore). Dev/staging only.
docker/scripts/dr-drill.sh

# DRY-RUN — capture baseline + snapshot, skip destroy/restore.
# Safe to run in any environment. Use this in CI smoke tests.
DRILL_SKIP_DESTROY=1 docker/scripts/dr-drill.sh
```

### Sample output (DRY-RUN, dev compose with seed data)

```
[dr-drill] ===== DR drill starting =====
[dr-drill] DRILL_SKIP_DESTROY=1 → DRY-RUN mode
[dr-drill] ----- step 1: baseline row counts -----
  users                     4 rows
  tokens                    25 rows
  liquidity_pools           22 rows
  lp_positions              19 rows
  transactions              2067 rows
[dr-drill] ----- step 2: snapshot -----
  snapshot took 2s, 12 MB
[dr-drill] DRY-RUN: skipping destroy / restore / verify
[dr-drill] ===== DR drill DONE =====
```

### Production cadence

- **Staging:** automated weekly cron — full drill with destroy/restore.
  Failure pages on-call.
- **Production:** quarterly drill in a copy-environment (snapshot → fresh
  cluster → restore → verify). Never destroy production directly.
- **Pre-release gate:** full drill in staging passes before any pre-prod
  promotion. CI enforces via the `DRILL_SKIP_DESTROY=1` smoke (validates
  the snapshot can be produced) on every PR touching `init-db.sql` or
  the Liquibase changesets.

### Reading the report

- **RTO** (Recovery Time Objective): время от `docker stop` до полностью
  восстановленного состояния. Sprint 11 target = < 5 минут на dev
  compose; production target with WAL-G = < 2 минут.
- **Row-count parity:** все 5 критических таблиц должны вернуть исходные
  count'ы. Любое несовпадение = data loss, расследование обязательно.
- **Drill duration creep:** если drill стал занимать > 2× времени за
  последний квартал — это сигнал растущей БД или деградирующего I/O.
  Plan capacity bump.

---

## Production rollout — WAL-G + S3 (TD-6 part 2, Sprint 10+)

The `pg-snapshot.sh` script above stays as the dev-grade story.
Production uses **WAL-G** to push base backups (and, in a follow-up
PR, continuous WAL stream) to S3-compatible storage. Скрипты,
override compose-файл и runbook лежат в репозитории и подключаются
оп-ин в продакшене.

### Что добавлено

| Файл | Назначение |
|---|---|
| `docker/scripts/walg-backup.sh` | Оркестратор `wal-g backup-push` против running `dlmm-postgres`. Cron-runnable, читает `.env.walg`. |
| `docker/scripts/walg-restore.sh` | Восстановление: `wal-g backup-fetch` + опциональный PITR (`--target-time`). Guard rail — отказывается работать на running контейнере без `--force`. |
| `docker/docker-compose.walg.yml` | Override-compose c WAL-G sidecar, который держит cron 02:30 UTC ежедневно. Дев `docker-compose up` не трогается. |
| `docker/.env.walg.example` | Шаблон env с `WALG_S3_PREFIX`, `AWS_*`. `.env.walg` gitignored. |
| `docs/runbooks/walg-disaster-recovery.md` | Step-by-step DR runbook для on-call SRE. |

### Запуск в production / staging

```bash
# 1. Заполнить creds
cp docker/.env.walg.example docker/.env.walg
$EDITOR docker/.env.walg   # WALG_S3_PREFIX + AWS_*

# 2. Поднять стек с override
cd docker
docker-compose -f docker-compose.yml -f docker-compose.walg.yml up -d

# 3. Проверить, что sidecar поднялся и cron установлен
docker logs dlmm-walg   # ожидаем "cron installed: daily 02:30 UTC"

# 4. (опционально) запустить backup вручную для smoke
docker exec dlmm-walg /scripts/walg-backup.sh
```

### Что осталось вне MVP (follow-up PR)

1. **Continuous WAL archiving** для PITR. Требует
   `archive_mode = on` + `archive_command = 'wal-g wal-push %p'` в
   `postgresql.conf`. Это требует рестарта Postgres + изменения
   main compose-файла, поэтому отделено в follow-up PR (нельзя
   ронять дев-стек). До тех пор `--target-time` в restore не имеет
   эффекта — восстанавливаем только base backup.
2. **Per-region cross-replication** — не нужно сегодня; включить
   когда Sber Treasury станет LP (см. P1-16) и RPO упадёт с часов
   до минут.
3. **Backup health Prometheus exporter** — sidecar пишет в
   `/var/log/walg-cron.log`, но Grafana алерт на
   `last_successful_backup_age_seconds > 26h` ещё не настроен.
   Сделать после следующего spike в Prometheus alerts.
4. **Custom postgres image с предустановленным wal-g** — сейчас
   sidecar делает runtime install (`apk add docker-cli`). Чище
   собрать собственный `postgres:16+wal-g` Dockerfile.
5. **Docker socket proxy** перед `dlmm-walg` — сейчас sidecar
   маунтит `/var/run/docker.sock` напрямую (полный API).
   Production should front it with `tecnativa/docker-socket-proxy`
   с whitelist `containers/json` + `containers/<id>/exec`.

См. также `docs/runbooks/walg-disaster-recovery.md` для подробного
DR-сценария.

---

## Backup contents

The `pg_dump --format=custom` snapshot is a single file containing:

- All `CREATE TABLE`, `CREATE INDEX`, `CREATE FUNCTION` DDL
- All row data for every table
- Sequence current values (so PK auto-increments don't restart)
- Object ownership (`dlmm` role)

It does **not** contain:

- Liquibase `DATABASECHANGELOG` — Liquibase rebuilds this from the
  changeset files on next service boot.
- Postgres roles / users (because we pass `-U dlmm` not `-U
  postgres`) — restore script must re-create the `dlmm` role
  separately.
- Server config (`postgresql.conf`, `pg_hba.conf`) — handled by
  Docker image / Helm chart.

## What we explicitly are NOT doing in this sprint

- **Cross-cluster logical replication.** Premature; single-tenant
  for now.
- **Per-table backups.** All-or-nothing snapshot is fine at our
  scale (4 MB today, projected 200 MB in 6 months).
- **In-tree dev restore script.** Dev users restore from one
  `pg_restore` invocation documented above; for production restore
  see `walg-restore.sh` + the DR runbook.

---

## Related work

- `docker/scripts/pg-snapshot.sh` — dev snapshot script.
- `docker/scripts/walg-backup.sh` — production WAL-G base-backup.
- `docker/scripts/walg-restore.sh` — production WAL-G restore.
- `docker/docker-compose.walg.yml` — opt-in sidecar override.
- `docker/.env.walg.example` — env template for WAL-G credentials.
- `docs/runbooks/walg-disaster-recovery.md` — DR runbook for SRE on-call.
- `.gitignore` — adds `docker/backups/` and `docker/.env.walg`.
- `OPS-SECRETS-RUNBOOK.md` — companion for secret management.
- `OutboxDispatcher.cleanup` (Sprint 9) — same 3:17 AM
  off-peak window.
