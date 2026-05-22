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

## Production rollout — what's NOT in scope for Sprint 9

The `pg-snapshot.sh` script and runbook are dev-grade. **Do not
ship to production as-is.** Production needs:

1. **Continuous archiving (WAL-G or pgBackRest)** for
   point-in-time recovery, not just nightly snapshots.
2. **Off-host storage** — S3-compatible (Sber Cloud Object Storage)
   with bucket versioning + lifecycle rule (30-day hot, 1-year
   cold, indefinite glacier).
3. **Cross-region replication** if SLA requires it (today no SLA
   exists; once Sber Treasury is an LP — see P1-16 — the RPO
   target will drop from "hours" to "minutes" and WAL streaming
   becomes mandatory).
4. **Encryption at rest** — pgcrypto for column-level on
   `users.password_hash` already in place; backup-level encryption
   via WAL-G's libsodium AEAD.
5. **Automated restore drills.** Quarterly restore-from-tape into
   a staging environment, with automated row-count and FK
   integrity checks. Without this, the backup might *exist* but
   not be *restorable* — common failure mode.
6. **Backup health monitoring.** Grafana alarm if
   `last_successful_snapshot_age_seconds` > 26h (one missed
   nightly + 2h grace).

These are Sprint 10+ work. Effort estimate: 5-8 days including
WAL-G provisioning + S3 bucket setup + restore-drill automation.

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

- **`pg_basebackup` + WAL streaming.** Heavyweight; defer until
  production storage decision (TD-6 part 2).
- **Cross-cluster logical replication.** Premature; single-tenant
  for now.
- **Per-table backups.** All-or-nothing snapshot is fine at our
  scale (4 MB today, projected 200 MB in 6 months).
- **In-tree restore script.** The dev restore path is one
  `pg_restore` invocation documented above; codifying it would be
  cargo-cult until prod backup story is finalised.

---

## Related work

- `docker/scripts/pg-snapshot.sh` — the snapshot script itself.
- `.gitignore` — adds `docker/backups/`.
- `OPS-SECRETS-RUNBOOK.md` — companion for secret management.
- `OutboxDispatcher.cleanup` (Sprint 9) — same 3:17 AM
  off-peak window.
