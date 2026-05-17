# Sprint 3 — Acceptance Protocol

**Date**: 2026-05-17
**Decision**: ✅ **ACCEPTED** (10 of 12 code tasks delivered, 2 blocked on legal)
**Stakeholders present**: PO, IT-lead, SA, BA, Compliance lead, SRE on-call

---

## Acceptance verdicts per deliverable

### Code tasks

| # | Deliverable | Verdict | Sign-off | Commit | Live evidence |
|---|---|---|---|---|---|
| 3.1 | `protocol_fee_pct = 5%` activation | ⏸ **BLOCKED** | PO | — | Waiting on 3.A legal memo (reg-frame impact) |
| 3.2 | Protocol fee distribution split | ⏸ **BLOCKED** | PO | — | Depends on 3.1 |
| 3.3 | Custody fee 5 bps p.a. scheduled job | ✅ ACCEPTED | Backend lead | `8ab5137` | Live: ivanov SBER −3424, admin +3424 after 5-day forced backdate. 39 user_balances rows accrued. Outbox event published. |
| 3.4 | Exit fee 10 bps on LP close | ✅ ACCEPTED | Backend lead | `e4fe2ab` + `91b5eb8` | Live: 100k SBER position closed → +100 X (10 bps) into pool fee accumulator. Log line "Exit fee charged: …feeX=100 (10bps)". |
| 3.5 | SRUB/CNY price oracle | ✅ ACCEPTED with caveat | SA | `91b5eb8` | CNY/USD/EUR mock feeds populate price_feeds with random-walk volatility. **Real MOEX ISS integration deferred to Sprint 4** (procurement of API access). |
| 3.6 | Hikari pool 20→50 + Postgres max_connections=400 | ✅ ACCEPTED | SRE | `e4fe2ab` | Live: all 6 JPA services report `hikaricp.connections.max = 50.0`. Postgres `SHOW max_connections` = 400. |
| 3.7 | k6 re-baseline with multi-user/multi-pool | ✅ ACCEPTED | SRE | `fc36649` | 72 RPS sustained (2.1× over Sprint 2), /pools p99 595ms (40× faster), /swap p99 1.32s (32× faster, threshold passed). |
| 3.8 | Liquibase migration convention + R#4 close | ✅ ACCEPTED | Backend lead | `1de03dd` | `docs/DB-MIGRATION-CONVENTION.md` + 7 READMEs. Changeset 004 EXECUTED on live DB (proves convention works). |
| 3.9 | Outbox extraction to dlmm-common | ✅ ACCEPTED | Backend lead | `e3378e1` | Single shared lib in `dlmm-common/outbox/`. -87 LOC net, 8 service-specific files deleted. Verified end-to-end: swap → 3 events (1 pool-engine + 2 token-service), per-service filter intact. |
| 3.10 | Same-pool row lock analysis memo | ✅ ACCEPTED | SA | `e4fe2ab` | `docs/ANALYSIS-SAME-POOL-LOCK.md` — 3 options (optimistic locking / per-bin / sharding) with effort × risk. Recommends Option A (Sprint 4 #4.7). |
| 3.11 | Prometheus alert rules | ✅ ACCEPTED | SRE | `e4fe2ab` | `docker/prometheus/rules/dlmm.yml` — 5 groups, 8 alerts: HikariPoolSaturated/NearLimit, OutboxBacklogGrowing, KafkaConsumerLagHigh, HighErrorRate, SlowSwapP99, DownstreamServiceDown. Loaded; PagerDuty wiring Sprint 4 #4.D. |
| 3.12 | Gateway /actuator/prometheus | ✅ ACCEPTED | SRE | `91b5eb8` | Gateway target UP in Prometheus (9/9 targets green, was 8/9). |

### Non-code (cross-functional)

| # | Task | Status | Owner |
|---|---|---|---|
| 3.A | Legal memo on protocol fee → broker-dealer reg-frame? | ⏸ Pending compliance | Compliance lead |
| 3.B | Sber Treasury pitch deck | ⏸ In progress | PO |
| 3.C | B2B portal design sketch (Figma) | ⏸ In progress | UX + BA |
| 3.D | USE-CASE-FX-HEDGE.md + scripts/demo-fx-hedge.ps1 | ✅ DONE | SA |
| 3.E | PO slide "FX hedge vs dealer desk" | ⏸ In progress | PO |
| 3.F | Compliance memo: B2B settlement reg-category | ⏸ Pending compliance | Compliance |

---

## Sprint-level acceptance criteria check

| Criterion | Sprint 3 plan target | Actual | ✓/✗ |
|---|---|---|---|
| Protocol fee active + revenue accruing | Yes (if legal memo green) | **Blocked** | ⏸ |
| Custody fee daily accrual job live | Yes | ✓ end-to-end verified | ✓ |
| Exit fee on LP close | 10 bps | ✓ +100 X on 100k withdrawn | ✓ |
| SRUB/CNY price feed | Real or stub | ✓ Stub live, real deferred to Sprint 4 | ✓* |
| Hikari saturation under load | Closed (re-baseline) | ✓ 2.1× throughput, 40× p99 latency | ✓ |
| Liquibase R#4 critical risk | Closed | ✓ Convention documented + applied | ✓ |
| Outbox code duplication | Removed | ✓ Shared lib, -87 LOC | ✓ |
| Prometheus alert rules | Defined | ✓ 8 alerts across 5 groups | ✓ |
| Gateway scrape target | UP | ✓ 9/9 targets green | ✓ |

10 of 12 code tasks delivered. The 2 blocked (3.1, 3.2) are
non-engineering blocked — compliance lead owes the memo. Sprint is
accepted on the condition that those reactivate the moment 3.A is
resolved (1-day code change).

---

## Bonus pre-existing bugs caught & fixed during acceptance verify

1. **token-service had no liquibase-core in pom** (since Sprint 1) — all
   changesets silently never ran. Fixed in `1de03dd`. Audit revealed
   3 more services without Liquibase + 2 with `ddl-auto: update`
   (transaction, notification) — added to Sprint 4 backlog as #4.10.
2. **Stale `save(b)` undid `@Modifying` UPDATE** in CustodyFeeAccrualService.
   JPA hidden-write trap. Fixed in `8ab5137`. Pattern documented in
   commit message; reusable lesson for any other scheduled accrual job.
3. **JWT autoconfig crashed price-oracle** because @ConditionalOnClass
   only checked OncePerRequestFilter, not jjwt. Fixed in `91b5eb8`.
4. **PriceFeed entity name mismatch** vs DB column (`price_change24h_pct`
   vs `price_change_24h_pct`). Fixed in `91b5eb8`.

These weren't on the Sprint 3 plan but blocked acceptance, so worth
calling out — total ~4 hours of unplanned debugging.

---

## Performance delta vs Sprint 2

| Metric | Sprint 2 close | Sprint 3 close | Δ |
|---|---|---|---|
| Throughput sustained | 34 RPS | 72 RPS | **2.1×** |
| /pools p99 | 23.22 s | 595 ms | **40×** |
| /swap p99 | 42.29 s | 1.32 s | **32×** |
| /dashboard p99 | 16.87 s | 5.6 s | 3× |
| Hikari max | 20 | 50 | 2.5× |
| Postgres max_connections | 100 | 400 | 4× |
| Prometheus targets UP | 8/9 | 9/9 | +gateway |
| Outbox shared LOC | 521 dup | 0 dup | extracted |

---

## Risk register delta

| # | Risk | Before | After | Notes |
|---|---|---|---|---|
| 4 | Liquibase MARK_RAN hack | Critical (16) | **Closed** | Convention doc + first compliant changeset live |
| 13 | Hikari saturation alarm | High (12, partial) | **Closed (partial → full)** | Alert rules now loaded |
| 18 | RPS budget unknown | Closed (Sprint 2) | Re-baselined | 2.1× ceiling raised |
| 20 | Treasury LP dominance / same-pool lock | New | Memo done, fix Sprint 4 #4.7 | Analysis ready for implementer |

**Critical risks open**: 0 (was 1 → #4 closed).
**Critical from monetization plan**: #19, #21, #22, #23 — none are
critical but all gate Sprint 4 monetization streams.

---

## Known defects (carried into Sprint 4)

1. **Protocol fee disabled** until 3.A legal memo resolves. Code stub
   ready, 1-day to activate.
2. **/dashboard p99 5.6s** under load — admin-bff aggregates 3
   downstream calls; same-pool row lock contributes. Closed by
   Sprint 4 #4.7 (optimistic locking).
3. **3 services without Liquibase** (transaction, fee, notification)
   still use init-db.sql / ddl-auto. Added as Sprint 4 #4.10.
4. **k6 swap_errors 33%** — seed-data ceiling (4 pools drain) +
   user-balance ceiling (3 users run out). Bigger seed or 22-pool
   rotation in next baseline.
5. **Docker VHDX still bloated** (~60 GB unused inside but not
   reclaimed to host). Run `Optimize-VHD` in admin PowerShell when
   convenient; not a sprint deliverable.

---

## Cross-functional signals captured

- **BA**: "Sprint 3 closes the technical credibility gap from Sprint 2.
  k6 numbers + custody fee + exit fee + outbox extract all give us
  real story bullets for the demo. Next sprint must turn this into
  **revenue stories** — FX hedges, Treasury LP, Spasibo."
- **Compliance**: "The Liquibase convention doc is exactly what audit
  asked for in February. Forward it to internal audit team."
- **SRE**: "PagerDuty wiring (4.D) waits on getting the on-call
  rotation defined. Need PO to nominate the rotation members."

---

## Decision

Sprint 3 **ACCEPTED**.

Begin Sprint 4 immediately after this meeting. Sprint 4 focus per
`docs/SPRINT-PLAN.md`: FX hedges pilot + B2B settlement v1 + start
addressing same-pool lock (#4.7).

---

*Signed off: PO + IT-lead + SA + BA + Compliance + SRE.*
*Sprint 4 kickoff immediately following. See `docs/SPRINT-4-KICKOFF.md`.*
