# Runbook — DR Failover Drill (G-35)

> Sprint 13 G-35. Quarterly disaster-recovery exercise that proves
> Postgres + Kafka are actually restorable in staging, validates the
> alert thresholds, and surfaces decay (stale runbooks, dead probes,
> rotated creds nobody told the on-call about).
>
> **Cadence:** every quarter (Q1/Q2/Q3/Q4), first Tuesday at 10:00 MSK
> в staging — never prod. SRE on-call drives, BE lead shadows.
>
> **NOT a synthetic test.** This drill takes down real components,
> measures real recovery. A failed drill is an outage incident
> against staging; document it the same way (post-mortem, owner,
> action items).

---

## Why this matters

The Sprint 13 backlog parked "TD-6 WAL-G S3 backup" as external-blocked.
Until WAL-G is wired into prod, the only protection against a Postgres
data dir loss is `pg_dump` (which `OPS-DB-BACKUP-RUNBOOK.md` covers).
The drill confirms that:

1. `pg_dump` restores actually work end-to-end (not just "the file exists").
2. Kafka stays consistent across a broker restart (in-flight messages
   either reach consumers or sit in the outbox).
3. Alert thresholds fire at the right moment (not 5 min late = silent SLO
   miss; not 30s early = pager-fatigue from normal startup transients).
4. The runbooks are still accurate (paths, hostnames, env vars don't
   drift faster than memory).

---

## Pre-drill checklist (run T-30 min)

- [ ] Announce in #sre-ops Slack: "🚨 DR drill staging 10:00 — expect
      pool-engine + token-service errors for ~5 min"
- [ ] Confirm staging traffic = synthetic only (no pilot client demo
      scheduled — check #demo-schedule)
- [ ] `git fetch origin && git log --oneline origin/main..HEAD` — note
      the staging commit SHA in the drill log
- [ ] Snapshot Prometheus state: `curl -s
      http://staging-prometheus:9090/metrics > /tmp/drill-before.txt`
- [ ] Open Grafana DLMM Overview dashboard in a second tab —
      visual reference during the drill
- [ ] Have the latest `pg_dump` URL ready (s3://sber-dlmm-staging-pg-backups/latest.sql.gz
      or wherever today's lives)
- [ ] Stopwatch app open — we measure recovery time per component

---

## Drill 1: Postgres kill+restore

**Tests:** that all 7 Spring services correctly close their Hikari pools
on DB loss, retry-loop appropriately on DB return, and that the data is
consistent post-restore.

### Step 1.1 — Kill Postgres (T+0)
```bash
docker stop dlmm-postgres
# OR in k8s: kubectl delete pod dlmm-postgres-0 -n staging
```

**Expected behaviour (within 30s):**
- All 7 Spring services start logging `HikariPool-1 ... Connection is not available`
- Healthchecks degrade: `/actuator/health` → DOWN with `db: DOWN`
- Grafana "Hikari Pool" panel: active connections → 0, pending → spikes
- API requests start returning 503 (DownstreamHealthIndicator catches it)

**Expected alerts (within 60s):**
- 🚨 `HikariPoolSaturated` (severity: critical) — see runbook
- 🚨 `DownstreamServiceDown` for db (severity: critical)

If an alert is missing: that's a finding. Add to post-drill action items.

### Step 1.2 — Validate everything is actually down (T+60s)
```bash
for s in dlmm-{user,token,pool,fee,transaction,notification,price-oracle}-service; do
  curl -s "http://staging:8080/actuator/health" \
       -H "X-Forwarded-Host: $s" | jq -r ".components.db.status // \"n/a\""
done
# Expected: all DOWN
```

### Step 1.3 — Restore (T+120s — give alerts 2min to fire)
```bash
docker start dlmm-postgres
# OR: kubectl rollout restart statefulset/dlmm-postgres
```

**Stopwatch:** time from `docker start` to:
- a) First Spring service reports `db: UP` again — **SLO ≤ 30s**
- b) All 7 services healthy — **SLO ≤ 60s**
- c) `/api/v1/pools` returns 200 — **SLO ≤ 90s**

Record actual numbers in the drill log.

### Step 1.4 — Data consistency check (T+5min)
```bash
docker exec dlmm-postgres psql -U dlmm -d dlmm -c \
  "SELECT COUNT(*) FROM transactions WHERE created_at > NOW() - INTERVAL '10 minutes'"
# Compare to /tmp/drill-before.txt: should be EQUAL (no in-flight loss)
```

**If counts differ:** transactions were swallowed during the outage.
That's a data-consistency bug. File P0 incident — outbox shouldn't allow
this; investigate why dispatcher didn't recover.

---

## Drill 2: Kafka kill+restore

**Tests:** that the outbox pattern (token-service + pool-engine) buffers
events during Kafka loss and drains automatically on recovery; that
notification-service consumer lag alerts fire correctly.

### Step 2.1 — Kill Kafka (T+0)
```bash
docker stop dlmm-kafka
```

**Expected behaviour:**
- Producer services (token-service, pool-engine) log
  `Producer.send() failed` but DON'T crash (outbox catches)
- `outbox_events` row count starts growing
- Notification-service consumer log: `Kafka broker disconnected, retrying...`

**Expected alerts (within 90s — longer than DB because Kafka is more
forgiving by design):**
- 🚨 `KafkaConsumerLagHigh` for notification-service group (severity: warning)
- 🚨 `OutboxBacklogGrowing` for both token-service + pool-engine outboxes (severity: warning)

### Step 2.2 — Inject test events (T+60s)
```bash
# Trigger 5 swaps as ivanov to make outbox-events accumulate
for i in 1 2 3 4 5; do
  curl -sX POST "http://staging:8080/api/v1/pools/<some-id>/swap" \
       -H "Authorization: Bearer $TOKEN" \
       -H "Content-Type: application/json" \
       -d '{"amount":"100","direction":"X_TO_Y"}'
done

docker exec dlmm-postgres psql -U dlmm -d dlmm -c \
  "SELECT service, status, COUNT(*) FROM outbox_events GROUP BY 1,2"
# Expected: rows in PENDING status (these are the queued events)
```

### Step 2.3 — Restore Kafka (T+180s)
```bash
docker start dlmm-kafka
# Wait for it to fully initialize (~30s)
```

**Stopwatch:**
- a) Outbox PENDING count → 0 — **SLO ≤ 90s** after Kafka up
- b) Notification-service consumer lag → 0 — **SLO ≤ 60s**
- c) Notification-service reports DOWN-then-UP on `/actuator/health`'s
     `kafkaConsumerLag` indicator

Record times.

### Step 2.4 — Verify no message loss
```bash
# Compare swap-event count published vs consumed:
docker exec dlmm-kafka kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic pool-events --from-beginning \
  --max-messages 100 --timeout-ms 5000 | wc -l
# Should match the number of swaps you fired in 2.2
```

---

## Drill 3 (optional, Q4 only): Full stack rebuild

**Tests:** that `docker-compose down -v && up -d --build` actually
brings the platform back to a functional state from cold cache.
Surfaces image-decay / seed-script drift.

```bash
cd docker/
docker-compose down -v   # WIPES VOLUMES — destroys all data
docker-compose up -d --build
```

**Stopwatch:** time until `/api/v1/pools` returns 200 with seeded data.
**SLO:** ≤ 5 min cold from scratch.

Failure modes typically caught:
- Seed SQL has a syntax error (only runs on first boot)
- A new env var added in code wasn't documented in `.env.example`
- A migration deletes/renames a column that the seed assumed exists

---

## Recovery time SLOs (summary)

| Component | First-instance-up SLO | Full-stack-healthy SLO |
|-----------|----------------------|------------------------|
| Postgres restart | ≤ 30s | ≤ 60s |
| Kafka restart | ≤ 60s | ≤ 90s |
| Cold-rebuild | n/a | ≤ 5 min |

If any SLO is missed, that's a quarterly finding. Triage:
- ≤ 2× SLO → minor, file action item
- 2–5× SLO → major, runbook needs an update
- > 5× SLO → critical, escalate to architecture review

---

## Alert thresholds to validate

Drill validates these thresholds fire at expected time, not stuck at
the historical (possibly stale) value:

| Alert | Threshold | Cooldown |
|-------|-----------|----------|
| `HikariPoolSaturated` | active=max for ≥ 30s | 5min |
| `DownstreamServiceDown` | `/health` DOWN ≥ 60s | 5min |
| `KafkaConsumerLagHigh` | total lag > 1000 ≥ 60s | 10min |
| `OutboxBacklogGrowing` | row count > 500 + growing | 5min |
| `HighErrorRate` | 5xx > 5% for 60s window | 5min |

If a threshold fired too early (during normal startup transient) → loosen.
If a threshold fired too late (after user impact) → tighten.

---

## Post-drill (T+30 min)

- [ ] Snapshot Prometheus state: `curl ... > /tmp/drill-after.txt`
      and diff vs `/tmp/drill-before.txt` for unexpected counter regressions
- [ ] Save Grafana panel screenshots: hikari, error rate, healthcheck,
      consumer lag (4 panels × before/during/after = 12 images)
- [ ] Fill drill log template (`docs/runbooks/drill-log-template.md`):
      drill #, date, on-call, recovery times, SLO pass/fail, action items
- [ ] File JIRA/Issues for any findings — assign owner + due date
- [ ] Announce in #sre-ops: "✅ DR drill complete: P=Xs K=Ys" + link to log

---

## What this drill does NOT cover

- **Multi-region failover** — single staging stack, no failover destination
- **Network partition tests** (chaos eng) — out of scope, future Sprint
- **Cloud-provider outages** — depends on Sber Cloud SLAs, separate runbook
- **Application-level deadlocks** — covered by load tests + Resilience4j
- **Data corruption** (silent disk corruption) — needs separate WAL-G
  point-in-time recovery drill, run after TD-6 lands

---

## Calendar

| Quarter | Date | On-call | Status |
|---------|------|---------|--------|
| Q1 2026 | 2026-01-07 | TBA | scheduled |
| Q2 2026 | 2026-04-01 | TBA | scheduled |
| Q3 2026 | 2026-07-01 | TBA | scheduled |
| Q4 2026 | 2026-10-07 | TBA | scheduled — INCLUDES Drill 3 |

First execution after this runbook lands: 2026-06-03 (Tuesday, 10:00 MSK)
— SRE lead drives, treat as initial calibration to set baseline SLOs.

---

*Companion docs: `OPS-DB-BACKUP-RUNBOOK.md`, `walg-disaster-recovery.md`,
`OPS-SECRETS-RUNBOOK.md`. Last reviewed 2026-05-26.*
