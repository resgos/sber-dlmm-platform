# Sber DLMM — Risk Register

Ranked by **inherent severity × likelihood** before mitigation.
Status columns reflect post-Sprint-8 state (refreshed 2026-07-08 per
audit AU-8). Update on every sprint review.

> Legend
> - **Severity**: 1 (cosmetic) → 5 (regulatory / data-loss / outage)
> - **Likelihood**: 1 (unlikely in a year) → 5 (will happen this quarter)
> - **Score** = S × L. Anything ≥ 12 is a blocker for go-live.

---

## Live before demo

| # | Risk | S | L | Score | Status | Owner | Action |
|---|------|---|---|-------|--------|-------|--------|
| 1 | **JWT secret leak** — default `change-me-in-production-...` could ship if `.env` isn't overridden | 5 | 2 | 10 | Mitigated: `${JWT_SECRET:?required}` fail-fast + `.env.example` template. Real secret never committed. Vault integration **deferred to Sprint 10+** (docker/.env enforced sufficient for pilot scale). | IT-lead | Vault migration tracked separately. |
| 2 | **Lost balance mutation on crash** — token-service updates balance then crashes before Kafka send → outbox/event lost, ledger drift | 5 | 3 | 15 | **CLOSED (Sprint 1 + Sprint 2).** Transactional outbox in both token-service and pool-engine. Cross-service kill-Kafka drill verified zero loss. Commits `d03742a`, `eed99a3`. | Backend lead | Done. |
| 3 | **Postgres single-node** — no replica, no PITR, container restart loses uncommitted ledger ops | 5 | 2 | 10 | OPEN. WAL streaming + nightly base backup deferred. Demo + pilot: docker volume only. Sprint 10+ ops track. | Ops | RPO/RTO contract owed by PO. |
| 4 | **Liquibase changeset model is "MARK_RAN"-hack** — future schema changes won't apply on existing DBs | 4 | 4 | 16 | **CLOSED (Sprint 3 #3.8).** Convention + per-changelog README + DB-MIGRATION-CONVENTION.md. PR checklist enforces. Re-analysis showed risk applies only to *new CREATE TABLE* changesets; ALTER + new tables work. AU-4 Sprint 8 admin_audit_log changeset followed the convention cleanly (commit `15f42d3`). | Backend lead | Convention enforced via PR review. |
| 5 | **Service-to-service auth bypass** — pool-engine accepts any valid JWT, no scope check on internal endpoints | 4 | 3 | 12 | **Partially mitigated (Sprint 8 AU-3).** Shared filter rejects refresh tokens (Sprint 6 dlmm-common consolidation). JWT revocation via Redis denylist (commit `5e55878`). `aud=internal` claim still not added — internal endpoints rely on K8s NetworkPolicy / VPC isolation in prod. **Sprint 10 work** if internal endpoints get exposed to less-trusted nets. | Backend lead | Score drops to 8 post-AU-3. |
| 6 | **Rate limit bypass** — only applied at gateway; direct service ports (8081–8088) are exposed in dev compose | 3 | 4 | 12 | OPEN. Compose ports stay open for dev convenience. Staging/prod docker-compose-prod.yml binds 127.0.0.1 (per Ops checklist), but **not pinned in CI**. | Ops | Add docker-compose validate step in CI — Sprint 9 SRE work. |
| 7 | **No CI/CD** — every change is built locally and pushed straight to demo | 3 | 5 | 15 | **CLOSED (Sprint 2 + Sprint 8 extensions).** GitHub Actions for backend (Maven + JDK 21), frontend (Vitest both UIs + hex-ratchet + Playwright e2e since Sprint 7), container-scan (Trivy weekly). Commits `3429ab2`, `ce8a601`, `f4445d7`. | DevOps | Required-status-check gating ON for `main` since Sprint 4. |
| 8 | **Front-end XSS via token list** — token name/symbol rendered without sanitisation | 4 | 2 | 8 | OPEN. AntD `Typography` does HTML-escape by default for plain strings; CSP not set. Mitigated by AntD's default escape; real fix = CSP header. **Sprint 10 hardening.** | UI lead | Add CSP + DOMPurify on user-supplied fields. |
| 9 | **CORS allows everything** — dev gateway had `Access-Control-Allow-Origin: *` | 3 | 4 | 12 | **CLOSED (Sprint 2).** Env-driven origin allowlist. Commit `6003805`. | UI lead | Done. |

## Operational

| # | Risk | S | L | Score | Status | Owner | Action |
|---|------|---|---|-------|--------|-------|--------|
| 10 | **Pool-engine TVL aggregates mojibake-encoded** — Cyrillic comments double-UTF-8 in JSON | 2 | 5 | 10 | OPEN. UI displays correctly because browsers auto-detect, but raw API responses look wrong. **Sprint 10** — force UTF-8 on Spring HttpMessageConverter + DB connection level. | Backend lead | Low priority. |
| 11 | **price-oracle is a stub** — values are seeded once, no real feed | 4 | 3 | 12 | **CLOSED (Sprint 3 #3.5 + Sprint 5 enhancements).** CbrRatesClient + MOEX feed. Verified by `CbrRatesClientTest`. Real CBR rates flowing through to price-oracle. | Backend lead | Done. |
| 12 | **Kafka consumer lag invisible** — no per-topic lag metric in /actuator/health | 3 | 3 | 9 | **CLOSED (Sprint 2).** `KafkaConsumerLagHealthIndicator` in notification-service. Commit `2b8a92a`. | Ops | Done. |
| 13 | **No DB connection-pool exhaustion alarm** — Hikari saturates silently under load | 3 | 4 | 12 | **CLOSED (Sprint 3 #3.11 + Sprint 8).** Prometheus alert rules in `docker/prometheus/rules/*.yml`. PagerDuty wiring Sprint 4 #4.D. Sprint 8 SRE: rules audit (no false-positives in 6 weeks of staging). | Ops | Done. |
| 14 | **/admin/dashboard cold-start ~2s** — first call after restart pays Spring lazy init + WebClient warmup | 2 | 5 | 10 | **CLOSED (Sprint 2).** `StartupWarmer` beans. Commit `6003805`. | IT-lead | Done. |

## Demo / presentation

| # | Risk | S | L | Score | Status | Owner | Action |
|---|------|---|---|-------|--------|-------|--------|
| 15 | **Live demo crashes on stage** — running on laptop, no failover | 4 | 3 | 12 | **CLOSED (4 demos shipped — Sprint 3, 5, 6, and PO mid-Sprint-7 trek).** Backup screenshots + recorded video in `docs/demo/`. Kill-and-restore drill rehearsed each sprint. | PO | Done. |
| 16 | **Audience asks about regulatory framework** — no answer prepared for CBR / 161-ФЗ compliance | 4 | 4 | 16 | **Partially closed (Sprint 5 process change + Sprint 6 5.D ЦФА memo).** Compliance lead in daily standup since Sprint 5 (per audit §5.4 — "Compliance partnership is a positive outlier, 3 sign-offs in-sprint"). 5.D ЦФА classification memo provides public answer for "is DLMM a ЦФА platform?". 152-ФЗ + 115-ФЗ Track 3 still open — Sprint 10 cascade. | PO | Q&A bank covers commercial defense; gaps in 152/115/161-ФЗ Track 3 (Sprint 10). |
| 17 | **Token-service goes DOWN mid-demo** — swap fails, balance drift if event lost | 5 | 2 | 10 | **CLOSED (Sprint 8 C-10).** Resilience4j circuit-breaker + retry on transaction-service + fee-service + pool-engine. fail-LOUD reconciliation diagnostic in B2BSettlementService for the half-success case. Commit `2b6a9fc`. | Backend lead | Done. |
| 18 | **Performance question on numbers** — "what's your RPS budget?" without baseline | 3 | 4 | 12 | **CLOSED (Sprint 2 + Sprint 3 re-baseline).** k6 baseline + numbers in `loadtest/README.md`. Sprint 3 Hikari bump dropped p99 ~3×. Multi-pool error rate (30% under 300 VUs) still tracked as **R#34**. | IT-lead | Done; see R#34. |

## Monetization-emerged (added Sprint 3 planning)

| # | Risk | S | L | Score | Status | Owner | Action |
|---|------|---|---|-------|--------|-------|--------|
| 19 | **Protocol-fee activation flips regulatory frame** — turning `protocol_fee_pct>0` may reclassify us from "internal clearing" to "broker-dealer" | 5 | 2 | 10 | **CLOSED (Sprint 3 #3.A legal memo).** Memo verdict: 5% protocol fee stays within "internal clearing" frame (Sber as platform, not principal). Protocol fee activated Sprint 3 #3.1. | Compliance lead | Done. |
| 20 | **Sber Treasury becomes single point of liquidity dominance** — same-pool row lock from k6 | 4 | 3 | 12 | **CLOSED (Sprint 4 #4.7).** Optimistic locking with @Version + bounded retry loop in SwapService. k6 multi-VU same-pool test now scales linearly. | SA + Backend lead | Done. |
| 21 | **B2B settlement category ambiguity** — sending SRUB corp-to-corp might require "transfer" reg-frame | 3 | 4 | 12 | **CLOSED (Sprint 3 #3.F memo).** Compliance verdict: classified as "internal transfer" with 5bps fee. Endpoint Sprint 4 #4.6 lives. | Compliance | Done. |
| 22 | **SBBOL integration delay** — corp FX-hedge depends on SSO from corp banking portal | 3 | 4 | 12 | **Partially mitigated (Sprint 7 stub-against-defaults).** SBBOL §7 q3 timeline pushed sandbox to Q4 2026. Sprint 7 #5.13 ships SbbolOidcProvider against assumed §2.3 defaults; Q4 config-flip when sandbox lands. **Risk: 1-2d refactor if real OIDC diverges** — Sprint 8 7.B PO trek mitigates by getting BU sign-off on defaults. | PO + Backend | Sprint 8 7.B + Q4 verification. |
| 23 | **Spasibo BU integration scope unknown** — SSPAS tokenisation requires upstream Loyalty changes | 3 | 4 | 12 | OPEN. Sprint 5 scoping done; minimal SSPAS mint/burn shipped Sprint 5 #5.1-5.3. **Write-back to Spasibo blocked on contract (Sprint 8 8.C continuation of Sprint 7 7.A).** Manual cashback design Sprint 9 if contract slips. | PO + Loyalty BU | Sprint 8 8.C close. |
| 24 | **Money market token reserve mismatch** — YSRUB must always be 1:1 backed; mis-attestation = run risk | 5 | 2 | 10 | OPEN (slid Sprint 7 → Sprint 9 per AU-1 rebalance, then Sprint 10). Daily attestation design memo Sprint 8 8.B. | Backend + Compliance | Pre-Sprint-10 design review. |
| 25 | **Index basket tracking error** — SBER10 rebalance slow/expensive → NAV drift | 3 | 4 | 12 | OPEN (slid Sprint 7 → Sprint 10 per AU-1 rebalance). Mitigation: rebalance threshold tunable, daily NAV publication. | Backend lead | Design review before Sprint 10. |

## Sprint 4-7 emergent risks

| # | Risk | S | L | Score | Status | Owner | Action |
|---|------|---|---|-------|--------|-------|--------|
| 26 | **AML detector false positives flood operators** — Sprint 6 #6.9 pattern detectors fire on real load without tuning | 3 | 3 | 9 | OPEN. Pure-function tests cover the detection logic; production false-positive measurement deferred. **Sprint 9 — sample 1k flagged transactions and tune thresholds.** Audit M-10. | SA + Backend | Sprint 9. |
| 27 | **PagerDuty rotation incomplete** — Sprint 3 alert rules fire but on-call rotation has gaps | 2 | 4 | 8 | **CLOSED (Sprint 7 R#13).** PagerDuty schedule populated with 4 nominees (Backend lead + 2 backend devs + SRE). Test alert fired+ack'd in staging Sprint 7 close. | SRE | Done. |
| 28 | **OTC pipeline 2/3 prospects (Sales)** — Sprint 9 OTC code launches against possibly-shrinking pipeline | 3 | 3 | 9 | OPEN. 2/3 signed as of Sprint 8 mid; PO trek 8.A targets 3/3 by Sprint 8 close. If still 2/3 at Sprint 9 kickoff, scope cut to 1 anchor client. | PO + Corp Sales | Sprint 8 close. |
| 29 | **Designer engagement single point of failure** — D-01 mockups arrived Sprint 6 day 8, no permanent designer | 3 | 4 | 12 | OPEN. Sprint 7 7.C → Sprint 8 8.D: formal contract close. If 8.D slips, Sprint 8 UX-DS-1 sweep does design-tokens audit without designer (works — Day 4-5 sweeps shipped 243 → 165 hex without designer present). | PO | Sprint 8 8.D. |
| 30 | **Pangolin 1.5 BIGINT InvariantTest divergence** — CI matrix flagged Postgres ≠ Pangolin divergence on BIGINT precision | 2 | 3 | 6 | OPEN (Sprint 7 carry → Sprint 8 carry, SRE bandwidth). 0.5d investigation. Doesn't block Sprint 9 launches; Pangolin compatibility is Track 1 (parking lot) anyway. | SRE | Sprint 8/9 SRE slot. |
| 31 | **Capacity vs ambition mismatch** — two consecutive sprints rebalanced + Sprint 7 mid-rebalance | 3 | 5 | 15 | **Partially mitigated (Sprint 7 retro AI-3 + Sprint 8 kickoff 27d ceiling).** Sprint 8 §11 retro question: did 27d ceiling hold? **Sprint 8 close measurement pending.** Track to Sprint 9. | IT-lead + PO | Sprint 8 retro to recalibrate. |
| 32 | **SBBOL stub-against-defaults Q4 refactor** — Sprint 7 5.13 ships against assumed OIDC issuer, scopes, signing | 3 | 3 | 9 | OPEN. Mitigation: BU sign-off on §2.3 defaults (Sprint 8 7.B). Risk window closes Q4 when sandbox arrives. | PO + Backend | Q4 2026. |
| 33 | **Resilience4j gap on fee-service + admin-bff + transaction-service** | 3 | 3 | 9 | **Partially closed (Sprint 8 C-10).** transaction-service + fee-service wired (commit `2b6a9fc`). **admin-bff carry-over — 6 separate WebClient beans need per-target extraction; 1d work Sprint 9.** | Backend dev 3 | Sprint 9. |

## Sprint 8 audit-emerged

These risks surfaced from the audit (`docs/SYSTEM-AUDIT-2026-06-17.md`)
and the Sprint 8 UX Hardening work that followed.

| # | Risk | S | L | Score | Status | Owner | Action |
|---|------|---|---|-------|--------|-------|--------|
| 34 | **Multi-pool k6 error rate 30%** — under 300 VU rotation across 4 pools | 3 | 4 | 12 | OPEN. Audit M-8. Diagnosis: seed-data ceiling (4 pools × ~3-5 LP each × 1k SRUB depth → ~20k SRUB total liquidity vs swap mix of 1k–10k per call). **Sprint 9 SRE — seed enrichment + re-baseline.** | SRE | Sprint 9. |
| 35 | **AML detector real-load FP measurement gap** | 3 | 3 | 9 | OPEN. See R#26 above (duplicate row deliberately, audit M-10 marker). | SA + Backend | Sprint 9. |
| 36 | **Frontend test coverage gap (pre-Sprint-8)** — HedgePage 10 tests, SwapPage 0, admin-ui 0 | 4 | 4 | 16 | **CLOSED (Sprint 8 C-7 + C-9).** SwapPage 15 cases (`14e11e1`), admin-ui 17 cases first-ever harness (`34fd2df`). Remaining gaps (admin-ui pages beyond Dashboard/Login + user-ui Pools/Positions/Transactions) tracked as Sprint 9+ filler. | Frontend + IT-lead | Done for hard gates. |
| 37 | **No distributed tracing** — Sleuth + Zipkin not wired | 3 | 3 | 9 | OPEN (audit AU-5). **Sprint 9 SRE.** Closes "show me the trace for swap X" debugging gap. | SRE | Sprint 9. |
| 38 | **Maven dependency-check missing in CI** | 3 | 3 | 9 | OPEN (audit AU-6). Trivy container scan exists weekly; dependency-check is per-build supplement. **Sprint 9 SRE.** | SRE | Sprint 9. |
| 39 | **Cross-app component duplication** — StatCard + formatRub in both UIs, drift inevitable | 2 | 4 | 8 | OPEN (audit M-1). Sprint 7 within-app dedup (`1e318e1`); cross-app needs npm workspaces. **Sprint 9+ — `dlmm-ui-common` package.** | Frontend | Sprint 9+. |
| 40 | **Design-token drift unchecked at PR time** | 3 | 5 | 15 | **CLOSED (Sprint 8 AU-2 + UX-DS-1 sweeps).** Stylelint hex-ratchet in CI (commit `f4445d7`). Baseline 243 → 165 (-32%) over two sweep commits (`f8eda9f`, `5aaf20f`). Hex-baseline.json prevents regression. | SRE + Frontend | Done; ongoing tightening. |
| 41 | **JWT no revocation on logout** | 4 | 3 | 12 | **CLOSED (Sprint 8 AU-3).** Redis denylist by jti + /auth/logout endpoint + filter check (commit `5e55878`). Fail-OPEN on Redis outage (acceptable trade — hiccup ≠ lockout). | Backend dev 2 | Done. |
| 42 | **No admin audit log** — sensitive mutations untraced | 4 | 4 | 16 | **CLOSED (Sprint 8 AU-4).** `@AdminAudit` AOP + admin_audit_log table + AOP test suite (commit `15f42d3`). 4 user mutations wired (block/unblock/KYC/role). Pool/token mutations Sprint 9+ extension when those endpoints add admin actions. | Backend lead | Done. |
| 43 | **Zero accessibility (audit C-1)** | 3 | 5 | 15 | **Partially closed (Sprint 8 UX-A11Y-1 wave 1).** 4 → 35 aria attributes across UIs (commit `8dfba95`). Hard gate "≥20 user-ui aria" met. **Wave 2 (skip-to-content, aria-current=page, full Sider tree audit) — Sprint 9.** Full WCAG 2.1 AA audit by Sber DS team — Sprint 10 per AU-7. | Frontend | Wave 2 Sprint 9. |
| 44 | **Zero mobile @media queries (audit C-2)** | 3 | 4 | 12 | **CLOSED (Sprint 8 UX-MOBILE-1 + UX-042).** 0 → 6 @media queries across both theme files (commit `167a046`). Tested at 320/375/414/768px. Playwright visual-regression — Sprint 9. | Frontend | Done; visual-regression follow-up Sprint 9. |
| 45 | **No language toggle (audit C-4)** | 2 | 3 | 6 | OPEN. C-4 i18n setup + RU extraction — **Sprint 8 last hard-gate item still pending; high risk of slip to Sprint 9.** EN translation Sprint 9+. | Frontend | Sprint 8/9. |
| 46 | **Backend vs frontend quality asymmetry (audit §5.1)** — backend 9/10, frontend 6/10 | 4 | 5 | 20 | **Substantially mitigated (Sprint 8 UX Hardening Sprint per AU-1).** This sprint's commits: AU-2 ratchet + AU-3 revocation + AU-4 audit + C-7 admin tests + C-9 SwapPage tests + C-10 r4j + UX-DS-1 wave 1+2 + UX-A11Y-1 + UX-MOBILE-1. **8 commits this session of frontend-quality investment.** Designer engagement (R#29) is the remaining structural gap — without permanent designer, drift returns. | PO + Designer | Score drops to 9 if R#29 closes Sprint 8. |

---

## Score summary (post-Sprint 8 refresh)

| Score band | Sprint 1 → 2 → +Monetization → Sprint 8 |
|---|---|
| Critical (≥ 15) | 4 → 1 → 1 → 3 ⤴ (#31 capacity, #43 a11y partial close, #46 asymmetry mitigating) |
| High (12–14) | 7 → 5 → 10 → 11 (composition shifted: many close, several new from audit) |
| Medium (8–11) | 5 → 4 → 6 → 11 |
| Low (< 8) | 2 → 2 → 2 → 4 |

**Closed since Sprint 2** (cumulative: 15 risks across Sprints 3-8):
#2 outbox, #4 Liquibase, #7 CI/CD, #9 CORS, #11 oracle, #12 Kafka lag,
#13 Hikari alerts, #14 cold-start, #15 demo, #17 token-svc down,
#18 RPS baseline, #19 reg-frame, #20 same-pool lock, #21 B2B reg-cat,
#27 PagerDuty, **#36 FE coverage, #40 design-token, #41 JWT revoke,
#42 admin audit, #44 mobile @media** (the last 5 all Sprint 8).

**Mitigated but live** (4): #5 service-to-service auth (Sprint 10 if
internal exposed), #16 reg Q&A (Track 3 cascade), #22 SBBOL (Q4),
#43 a11y (wave 2 Sprint 9).

**Newly added Sprint 8** (10 risks from audit): #34-45 from the C-*/M-*
list. #46 backend/frontend asymmetry is the headline — Sprint 8
hardening was the deliberate counter.

**Still critical (score ≥ 15)**:
- #31 Capacity vs ambition — Sprint 8 retro will tell.
- #43 a11y (15 reduced to ~10 post-wave-1; wave 2 closes).
- #46 quality asymmetry — drops to 9 if R#29 designer contract closes
  Sprint 8 8.D.

*Last refreshed: 2026-07-08 — audit AU-8 refresh after Sprint 8 Day 5.
Added Sprint 4-7 emergents (#26-33) and Sprint 8 audit-emerged (#34-46).
Owners self-update; PO reviews at sprint close.*
