# Sprint 3 — Kickoff & Day 1 Progress

**Date**: 2026-05-16
**Kickoff context**: immediately after Sprint 2 acceptance ceremony.

---

## Attendees & assignments

| Role | Person | Sprint 3 lane |
|---|---|---|
| PO | – | Legal memo (3.A), Treasury pitch (3.B), daily stand-ups, weekly cross-cutting review |
| Backend lead | – | Liquibase split (3.8), outbox extraction (3.9), custody/exit/oracle/protocol fee stack |
| Backend dev 2 | – | Exit fee (3.4), protocol fee distribution scaffolding (3.2, conditional) |
| Backend dev 3 | – | SRUB/CNY oracle (3.5), custody fee job (3.3) |
| Frontend | – | Waiting on B2B portal design (3.C), then Sprint 4 #4.1 FX hedge UI |
| SRE | – | Hikari (3.6), k6 re-baseline (3.7), Prometheus rules (3.11), Gateway actuator (3.12) |
| SA | – | Row lock analysis (3.10), FX hedge demo prep (3.D), B2B compliance memo coord (3.F) |

Capacity ~6 task slots per sprint at this team size.

---

## Day 1 — completed work

### ✅ #3.6 Hikari pool bump (SRE lane)

Increased `maximum-pool-size` from default 20 to 50 across 6 JPA services:
pool-engine, token-service, user-service, fee-service,
transaction-service, notification-service. Postgres `max_connections`
raised to 400 in compose (default 100 would cap us at 100 / 6 = ~16
per service, defeating the purpose).

Files:
- `dlmm-*/src/main/resources/application.yml` (6 services)
- `docker/docker-compose.yml` (postgres `command`)

### ✅ #3.4 Exit fee 10 bps on LP close (Backend lane)

Added configurable `dlmm.fees.lp-exit-bps` (default 10) to
`LiquidityService.removeLiquidity`. On withdraw, the fee is deducted
from principal (not from fees earned), credited to the pool's
`total_fees_collected_x/y` accumulator. Caller log records the
amount + bps.

This is the first **already-active** revenue stream — it works
without legal memo (it's a fee on the user's own action, not a
protocol skim).

Files:
- `dlmm-pool-engine/.../service/LiquidityService.java`

### ✅ #3.12 Gateway `/actuator/prometheus` (SRE lane)

Added `spring-boot-starter-actuator` to gateway pom, enabled
`management.endpoints.web.exposure.include` for the standard set.
JWT skip-list already had `/actuator/**` so gateway routes don't
intercept the scrape path. Closes the 1 DOWN Prometheus target from
Sprint 2.

Files:
- `dlmm-gateway/pom.xml`
- `dlmm-gateway/src/main/resources/application.yml`

### ✅ #3.10 Same-pool row lock analysis memo (SA lane)

Documented the bottleneck and three viable approaches (optimistic
locking, per-bin granularity, horizontal sharding). Recommendation:
Option A (optimistic) in Sprint 4 #4.7 as immediate fix; spike
Option B in Sprint 5; decision gate before Sprint 6 MM rebate.

Files:
- `docs/ANALYSIS-SAME-POOL-LOCK.md`

### ✅ #3.11 Prometheus alert rules (SRE lane)

Created `docker/prometheus/rules/dlmm.yml` with 7 alert rules in
5 groups: Hikari saturation + near-limit, outbox dispatcher health
(proxy until proper metric exposed in Sprint 4), Kafka consumer lag,
5xx error rate, swap p99 latency, downstream service down.

PagerDuty / Slack wiring still owed by Sprint 4 #4.D.

Files:
- `docker/prometheus/rules/dlmm.yml`
- `docker/prometheus/prometheus.yml` (rule_files: enabled)
- `docker/docker-compose.yml` (mount rules dir)

---

## Day 1 — blocked / waiting

### ⏸️ Verification of all of the above

Docker Desktop daemon entered an unresponsive state mid-rebuild (500
Internal Server Error from the API endpoint). All code changes are
saved and committed, but **live verification** (timing comparison
after Hikari bump, alert rules loaded by Prometheus, exit fee tested
on real swap) is pending a Docker Desktop restart on the dev machine.

**Recovery steps for the dev**:
1. Restart Docker Desktop via tray icon
2. `cd docker && docker-compose down`
3. `docker-compose build dlmm-pool-engine dlmm-token-service dlmm-user-service dlmm-fee-service dlmm-transaction-service dlmm-notification-service dlmm-gateway`
4. `docker-compose up -d`
5. Wait for all to be UP
6. Re-run Sprint 2 k6 baseline → expect ≥3× improvement
7. `curl http://localhost:9090/api/v1/rules` → expect DLMM groups loaded

### ⏸️ #3.1 + #3.2 protocol_fee_pct activation

Blocked by **3.A legal memo** (Compliance owes it by Sprint 3 day 5).
Code stub for fee distribution split planned for Sprint 3 day 6
pending memo outcome.

### Pending in this sprint (not yet started)

- #3.3 Custody fee scheduled job (Backend lane, 3 days)
- #3.5 SRUB/CNY MOEX oracle (Backend + SA, 4 days, depends on procurement)
- #3.7 k6 re-baseline with multi-user/multi-pool (SRE, 2 days, depends on #3.6 verification)
- #3.8 Liquibase migration split (Backend lead, 4 days — **critical R#4**)
- #3.9 Outbox extraction to dlmm-common (Backend lead, 3 days)

---

## Non-code blockers tracked by PO (weekly stand-up)

| # | Item | Owner | Status | Deadline |
|---|---|---|---|---|
| 3.A | Legal memo: protocol fee → broker-dealer? | Compliance lead | Requested day 1 | Sprint 3 day 5 |
| 3.B | Sber Treasury pitch deck + meeting | PO + CFO | Deck drafting day 1 | Treasury meeting in Sprint 4 |
| 3.C | B2B portal Figma + flows | UX + BA | Brief sent day 1 | End of Sprint 3 |
| 3.D | USE-CASE-FX-HEDGE.md + demo script | SA | Stub exists (Sprint 2 #b066a3c) | Sprint 3 day 10 |
| 3.E | PO slide "FX hedge vs dealer desk" | PO + Corp Sales | Numbers being gathered | End of Sprint 3 |
| 3.F | Compliance memo on B2B settlement frame | Compliance | Brief sent day 1 | End of Sprint 3 |

---

## Risks raised on day 1 (added to risk register)

- **Docker Desktop reliability on dev workstations** — Sprint 3 day 1
  ran into a daemon hang during rebuild that required manual UI
  restart. Not a production risk, but slows dev cycle. Track for
  Sprint 4 to evaluate Linux dev VMs or build-in-CI option.

---

## Velocity check

Pre-Sprint estimate: ~6 task slots, 18 total items (12 code + 6 non-code).
Day 1 burn-down: 4 code items completed (3.4, 3.6, 3.10, 3.11, 3.12 —
counting 3.10 the memo), plus all 6 non-code items kicked off.

Day-1 progress is **on track** assuming Docker Desktop recovers and
verification + k6 re-baseline complete by day 3.

---

*Recorded by: IT-lead. Daily updates appended below this line until
sprint close.*
