# Sprint 2 — Closing Review

**Window**: 2 weeks (delivered same-day in the demo branch)
**Branch**: `claude/elated-elgamal-dba521`
**Range**: `83f066a` (Sprint 1 close) → `6003805` (Sprint 2 #7)
**Risks closed**: 6 (and 1 partial)

---

## What landed

| # | Task | Commit | Outcome |
|---|------|--------|---------|
| 1 | Pool-engine outbox + shared-table service filter | `eed99a3` | Both services write to shared `outbox_events` with per-service dispatcher filter. Cross-service kill-Kafka drill verified zero loss. |
| 2 | CI/CD pipeline (3 GitHub Actions) | `3429ab2` | Backend + frontend + Trivy container scan. Path filters keep minutes cheap. Concurrency cancels stale runs. |
| 3 | k6 load test baseline | `c230ef2` | Honest first-cut: 34.5 rps sustained, /pools p99 23s @ 300 VUs. Diagnosis + Sprint 3 plan in README. |
| 4 | Kafka consumer lag healthcheck | `2b8a92a` | `KafkaConsumerLagHealthIndicator` in notification-service via AdminClient. DOWN at lag > 1000. Drill verified UP→DOWN→UP cycle. |
| 5 | Prometheus + Grafana stack | `618bf9f` | Auto-provisioned datasource + DLMM Overview dashboard. 8/9 targets scraping. |
| 6 | Startup warmers | `6003805` | pool-engine 808ms, admin-bff 803ms warm at startup. Dashboard cold-start eliminated for first user. |
| 7 | CORS dev/prod split | `6003805` | `dlmm.cors.allowed-origins` env-driven. `application-prod.yml` locks to `sber-online.ru`. Verified dev allowed, evil rejected. |

---

## Acceptance check (live, snapshot at sprint close)

```
Dashboard warm:        72–148 ms        (target < 2s ✓)
/pools warm:           27–32  ms        (target < 1s ✓)
Outbox events:         pool-engine 35 published, token-service 122 published, 0 unpublished
Deep health (pool-engine): all 9 components UP (db, redis, ping, tokenService, userService, circuit-breakers idle, liveness, readiness, diskSpace)
Kafka consumer lag:    totalLag=0 (threshold 1000), status=UP
Prometheus targets:    8 services UP, gateway DOWN (404 reactive actuator — Sprint 3)
Grafana:               http://localhost:3030 → 200 OK
GitHub workflows:      backend.yml, frontend.yml, container-scan.yml all valid
```

## Risks closed

| # | Risk | Score before |
|---|------|--------------|
| 2 | Lost balance mutation on crash | 15 |
| 7 | No CI/CD | 15 |
| 9 | CORS allows everything | 12 |
| 12 | Kafka consumer lag invisible | 9 |
| 14 | Cold-start ~2s | 10 |
| 18 | No RPS baseline | 12 |

Plus partial close on **#13 Hikari saturation alarm** — metrics now scraped, alert rules pending.

**Score summary**: Critical 4 → 1, High 7 → 5. The remaining critical
is #4 (Liquibase migration split), which is structural and on Sprint 3.

## What didn't land (carry-over)

- **Gateway /actuator/prometheus** — gateway is reactive (Spring Cloud
  Gateway), needs a separate webflux-style actuator config. Currently
  shows DOWN in Prometheus. Workaround: gateway's HTTP metrics are
  recoverable from downstream service scrapes.
- **Prometheus alert rules** — file directory exists (`docker/prometheus/`)
  but no `rules/*.yml` yet. Needs load-test data to pick thresholds.
- **Outbox extraction to dlmm-common** — entity scanning ergonomics
  make this a multi-pom change; called out as TODO in the duplicated
  `OutboxEvent` class.

## Honest issues exposed

The k6 baseline did its job — broke under load. The numbers below are
the **current ceiling**, not the floor we ship at.

```
p99 /pools     23.22s  @ 300 VUs / 200 RPS target
p99 dashboard  16.87s
p99 swap       42.29s
swap success    3.5%   (test-design pile-up: all VUs deduct same balance)
sustained RPS  34.5    (target 200)
```

Three root causes, ranked by fix effort:

1. **Hikari pool: 20 → 50** — one YAML line per service. ~5 min change.
2. **k6 mix needs user-rotation** — currently all VUs hit ivanov's
   balance, after ~30 swaps it's empty and every subsequent call 4xx's.
   Test-design, not infra. ~30 min change to baseline.js.
3. **Same-pool row lock** — multiple swaps against `c0000000-...001`
   serialise on the pool row. Real bottleneck. Either: (a) optimistic
   locking + retry, (b) per-bin lock granularity, (c) accept and shard
   pool-engine horizontally. Architectural call for Sprint 3.

## Demo-readiness delta

| Aspect | Sprint 1 close | Sprint 2 close |
|---|---|---|
| Critical risks | 4 open | 1 open (#4 Liquibase) |
| Dashboard cold-start | 2.9s | <1s for first user (warmer) |
| Lost-event window | open (token-service only) | **closed both services** |
| CI gating | none | green PRs required |
| Metrics observability | actuator/health only | Prometheus scraping 8 services + Grafana dashboard |
| Load test budget | unknown | documented baseline + Sprint 3 targets |
| CORS in prod plan | dev-only YAML | profile-driven split, prod overrides ready |

Net: PO can demo with the same confidence as Sprint 1 close *plus*
"here are the numbers, here's the dashboard, here's the CI, here's the
drill — kill Kafka, kill a downstream, watch it recover."

## Recommended Sprint 3 focus

Trade-offs to the PO at the next planning:

1. **#4 Liquibase split (critical)** — proper migration story so
   schema changes don't get MARK_RAN'd silently. ~1 week effort.
2. **Outbox extraction to dlmm-common + dispatcher metrics** — close
   the partial #13 risk fully. ~3 days.
3. **k6 re-baseline after Hikari bump + multi-user mix** — turn the
   honest 34 rps into a credible 100+ rps. ~1 day.
4. **Prometheus alert rules + on-call rotation doc** — the metrics
   are useless without page-out triggers. ~2 days.
5. **Gateway actuator (webflux) + same-pool row lock analysis** —
   the two carry-overs called out above. ~3 days.

Plus the **#16 regulatory Q&A** (compliance), which is non-tech
but owed by PO + legal before any external-facing demo.

---

*Owner: IT Lead. Update at every sprint close.*
