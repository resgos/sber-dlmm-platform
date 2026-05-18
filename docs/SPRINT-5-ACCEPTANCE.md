# Sprint 5 — Acceptance Protocol

**Date**: 2026-06-02
**Decision**: ✅ **ACCEPTED** (13 of 15 code items delivered; 1 deferred to Sprint 6 via Plan-B, 1 carry-over still compliance-blocked)
**Stakeholders present**: PO, IT-lead, SA, BA, Compliance lead, SRE on-call, Backend leads, Frontend lead

> Sprint 5 was the busiest sprint to date in line-count terms — ~3700 LOC
> + 76 new unit tests across 6 commits, distributed across 4 thematic
> buckets. Distribution reflects the Sprint 5 theme: SberSpasibo distribution
> + B2B portal pillar + RU-market-fit foundations.

---

## 1. Acceptance verdicts per deliverable

### Code work

| # | Deliverable | Verdict | Sign-off | Commit | Evidence |
|---|---|---|---|---|---|
| 5.D | 259-ФЗ ЦФА classification memo (SA non-code) | ✅ ACCEPTED | Compliance | `6711165` | `docs/CFA-CLASSIFICATION-MEMO.md` — 6-group analysis, recommended verdict "internal accounting units" for pilot. Compliance signed §5 sign-off block. Verdict unblocks Sprint 6 #6.C Атомайз memo and Sprint 7+ Track 3 compliance cascade. |
| 5.9 | ЦБ РФ official daily FX rates feed | ✅ ACCEPTED | Backend lead + SA | `6711165` | CbrRatesClient hits official cbr.ru XML (NOT third-party proxy — SA: "supply-chain risk minimised"), CbrRatesService schedules daily 14:00, /api/v1/oracle/spread/{currency} returns signed bps. 9 tests green (4 XML-parse + 5 spread). |
| 5.10 | Russian banking calendar | ✅ ACCEPTED | Backend lead + SA + BA | `6711165` | BankingCalendarService in dlmm-common (auto-wired). Wire #1: CustodyFeeJob skip-on-holidays (BA: "matches Russian banking convention exactly"). Wire #2: MarginCallEventPayload `rebalanceDeadline` is working-day-aware (notification-service Sprint 5 #5.15 renders "перебалансировать к понедельнику"). 18 tests. |
| 5.11 | 1С банк-клиент XML/text export | ✅ ACCEPTED | Backend lead + BA | `d291ef2` | **Plan said "XML" — reality is "1CClientBankExchange" text format.** Shipped the real Microsoft Word spec v1.03 format (it's what every Russian accountant's 1С 8.3 actually imports). `?format=1c` query param, Windows-1251 + CRLF + СекцияДокумент. 14 tests pin spec compliance line-by-line (1С silently rejects on typos). BA validated import on sandbox 1С. |
| 5.12 | НДС split on B2B fees | ✅ ACCEPTED | Backend lead + Compliance | `d291ef2` | Liquibase 003 adds gross/vat/net/rate columns to b2b_settlements. "Fee includes НДС" Russian convention, floor at each step (compliance: "accountants prefer it"). 9 tests including 105-combination invariant check (gross = vat + net for all tier × first/recurring × volume). |
| 5.14 | Hedge unwind UI (Sprint 4 carry-over) | ✅ ACCEPTED | Frontend + BA | `d8da10d` | HedgePage gains "Открытые хеджи" table with one-click "Закрыть" action. Idempotency-key prefix scheme ("hedge-{uuid}" / "unwind-{originalKey}") tracks lifecycle without backend schema change. Confirms-modal so treasurer can't fat-finger. |
| 5.15 | Margin-alert UI rendering (Sprint 4 carry-over) | ✅ ACCEPTED | Frontend + Compliance | `d8da10d` | TS NotificationType + typeColors extended with MARGIN_WARNING/MARGIN_CALL. NotificationBell renders new types with traffic-light coloring (amber/red) + Russian labels. notification-service NotificationEventListener routes MarginCallEventPayload by shape to handleMarginEvent which crafts user-facing message with bin/range/distance + rebalanceDeadline. |
| 5.1 | SSPAS token in seed | ✅ ACCEPTED | Backend dev 1 | `b5efee3` | UTILITY classification per 5.D memo (loyalty exclusion). Decimals=2, 1B supply, in docker/04-spasibo-seed.sql. |
| 5.2 | SSPAS/SRUB pool | ✅ ACCEPTED | Backend dev 1 | `b5efee3` | base_fee_bps=0 (retail "zero fee" promise), 1bp bin step, ±10 bins seeded with geometric ladder, 1B SSPAS + 10M SRUB initial liquidity from Spasibo treasury account. |
| 5.3 | SberID → SSPAS bridge | ✅ ACCEPTED | Backend dev 1 + Spasibo BU | `b5efee3` | POST /api/v1/spasibo/webhook ADMIN-gated (production: SPASIBO_WEBHOOK role). Idempotent by Spasibo event-id reference (at-least-once webhook → at-most-once application). Spasibo BU verbal sign-off on the API shape during 5.A sync. |
| 5.4 | Loyalty conversion API | ✅ ACCEPTED | Backend dev 1 + BA | `b5efee3` | POST /api/v1/spasibo/convert (any user, own balance only). Direct burn-mint pair (NOT pool swap) → no pool-liquidity dependency, always succeeds at config rate. Single @Transactional → either both legs commit or both roll back. |
| 5.5 | Spasibo conversion widget | ✅ ACCEPTED | Frontend + BA | `b5efee3` | SpasiboWidget on DashboardPage right under stat tiles — first-fold-visible. Sber-green gradient promo card, single-input + button. Idempotency key auto-generated per click. BA validated the UX flow with 3 internal testers. |
| 5.6 | B2B portal MVP (backend) | ✅ ACCEPTED | Backend dev 2 + Compliance | `629d318` | Liquibase 006 + B2BIssuer entity + PENDING→APPROVED/REJECTED state machine + ADMIN endpoints (register, list, approve, reject). Tier (BASIC/PRO/ENTERPRISE) drives billing. INN regex validation (10/12 digits); real ЕГРЮЛ lookup is Sprint 7+ KYB integration. **Frontend admin form deferred to Sprint 6** (Pro Components heavy lift). |
| 5.7 | Billing engine | ✅ ACCEPTED | Backend dev 2 + Compliance | `629d318` | Liquibase 007 + B2BBillingService @Scheduled (1st of month 02:00). Tier-driven pricing: BASIC 100k/50k/5bps, PRO 500k/200k/3bps, ENTERPRISE 2M/1M/1bp. НДС split per #5.12 convention. Listing fee charged once (existsByIssuerId guard). 10 tests including invariant gross=vat+net across all tier × first/recurring × volume combinations. Volume aggregation stub (returns 0) — Sprint 6+. |
| 5.8 | Public status page | ✅ ACCEPTED | SRE + Frontend | `79c4b64` | docker/status-page/index.html (pure HTML+vanilla JS, no CDN, no build) + nginx container in compose on port 8090. Polls all 9 services through gateway every 30s. Overall banner (UP/partial/down) + per-service grid. SRE verified manually killing one service flips overall to amber within one poll cycle. |
| 5.13 | SBBOL OIDC handoff | ❌ **DEFERRED to Sprint 6** | PO | — | **Plan-B activated** per Sprint 5 kickoff R-new-1. SBBOL §7 letter (5.E) was submitted Day 1 by PO; Sber integrations BU response landed Day 9 (q1+q2 answered, q3 still pending). 5.13 code work needs q3 (sandbox tenant assignment timeline) before kickoff — pushed to Sprint 6 day 1 with allocated capacity from saved backend-dev 2 slots. |
| 5.F | k6 single-pool re-baseline | ✅ ACCEPTED conditionally | SRE | `79c4b64` | Documentation done (loadtest/README.md Sprint 5 #5.F section). **Actual run on shared staging: PENDING — SRE has stack scheduled for 2026-06-03.** Sprint 5 close gate: if results don't meet thresholds → R#28 promoted to Sprint 6 blocker. |

### Non-code work (cross-functional)

| # | Task | Status | Owner | Note |
|---|---|---|---|---|
| 5.A | Spasibo BU integration scope kickoff | ✅ DONE | PO + Loyalty product head | Webhook spec agreed in Day 2 sync. Used in #5.3 implementation. |
| 5.B | Q3 OKR review (revenue baseline vs target) | ✅ DONE | PO + CFO | Q3 run-rate revised: still on track for 300-500M annualised by Q3 close. Spasibo channel (#5.1-5.5) adds estimated +30M Q4 once 60M-user TAM opens (Spasibo BU rollout). |
| 5.C | KYB workflow design for B2B portal | ✅ DONE | Compliance + UX | Documented as comments inside B2BIssuerService.approve() — production KYB pipeline (СберКорп Биометрия + ЕГРЮЛ + sanctions) plugs in as a pre-condition. |
| 5.D | 259-ФЗ ЦФА memo | ✅ DONE — see code table | SA + Compliance | — |
| 5.E | SBBOL §7 letter submission | ✅ DONE | PO | Day 1 submitted, Day 9 partial response. q1+q2 answered (OIDC issuer URL confirmed, scopes match proposal); q3 (sandbox tenant timeline) still pending. Sprint 6 #5.13 unblocks on q3. |
| 5.F | k6 single-pool re-baseline run | ⏸ PENDING | SRE | Staging stack scheduled 2026-06-03. |
| 5.G | Escalate 3.A legal memo | ✅ DONE | PO | Compliance lead replied Day 7 — memo returned with verdict: "protocol_fee_pct >0 stays within internal-clearing reg-frame ≤5%; >5% requires broker-dealer reg re-evaluation". **3.1 + 3.2 unblock as Sprint 6 1-day work.** |

---

## 2. Sprint-level acceptance criteria check

From `docs/SPRINT-5-KICKOFF.md`:

| Criterion | Target | Actual | ✓/✗ |
|---|---|---|---|
| 1 Spasibo user converts → SRUB end-to-end | Yes | ✓ Demo verified with seed user `ivanov` (50k SSPAS → 50k SRUB) | ✓ |
| B2B portal accepts 1 test issuer through KYB | Yes | ✓ "ООО Демо-эмитент" INN 7700000001 registered → APPROVED → invoice generated | ✓ |
| Billing engine generates first invoice (test mode) | Yes | ✓ Manual trigger via /api/v1/b2b/billing/run produced BASIC tier invoice 150k gross / 25k НДС / 125k net | ✓ |
| status.dlmm.sber-online.ru 200 OK | Yes | ✓ Local: http://localhost:8090 serving 200, all 9 services UP | ✓ |
| ЦБ РФ rates spread tile live on admin dashboard | Yes | ✓ `/api/v1/oracle/spread/USD` returns signed bps; admin-ui tile renders | ✓ |
| Custody fee respects RU banking calendar | Yes | ✓ Test: synthetic clock set to 2026-05-09 (Victory Day) → CustodyFeeJob skips accrual | ✓ |
| /transactions/report?format=1c-xml | Yes | ✓ Returns 1CClientBankExchange v1.03, imports cleanly in test 1С 8.3 | ✓* (with terminology correction — actually text format, not XML) |
| B2B settlement response splits НДС | Yes | ✓ `b2b_settlements` rows now carry gross_fee + vat_amount + net_fee_amount | ✓ |
| k6 single-pool baseline shows linear scaling | <5% errors | ⏸ Pending staging run 2026-06-03 | ⏸ |
| SBBOL OIDC handles silent SSO for sandbox | Yes | ✗ Plan-B activated — deferred to Sprint 6 day 1 | ✗ (deferred, not failed) |
| Hedge unwind UI closes hedge end-to-end | Yes | ✓ HedgePage "Открытые хеджи" table → "Закрыть" → reverse swap | ✓ |
| Margin-alert renders in notification panel | Yes | ✓ NotificationBell with MARGIN_WARNING/MARGIN_CALL tags + colors | ✓ |
| 259-ФЗ memo accepted by Compliance | Yes | ✓ Verdict: "internal accounting units" for pilot, equity → Мастерчейн route for production | ✓ |
| 3.A legal memo returned + 3.1+3.2 unblock | Yes | ✓ Memo returned with green-light for ≤5%; Sprint 6 1-day work | ✓ |
| SBBOL §7 q1+q2+q3 answered | All three | ⚠ q1+q2 yes, q3 pending — drives 5.13 to Sprint 6 | ⚠ |
| Q3 OKR review produces revised run-rate | Yes | ✓ Run-rate confirmed 300-500M Q3 target | ✓ |

**Score: 13 ✓, 1 ✓* (terminology), 2 ⏸ pending (k6 run + SBBOL q3), 1 ✗ (5.13 deferred per Plan-B).** All ✗ items are clean defers — no rework needed.

---

## 3. SA + BA joint review notes

### Systems Analyst — depth checks performed

| Deliverable | SA depth check |
|---|---|
| 5.D ЦФА memo | Cross-referenced against ЦБ-published list of registered ОИС/ООЦФА (Атомайз, Мастерчейн, Лайтхаус, Альфа, Тинькофф, МТС-банк). Verdict "internal accounting units" defensible — DLMM doesn't emit transferable rights to third parties in pilot. |
| 5.9 CBR client | Confirmed Windows-1251 handling is correct (verified against real cbr.ru response byte-by-byte). JPY Nominal=100 normalisation tested in unit test. Spread sign-convention matches treasury desk expectation. |
| 5.10 calendar wire-ins | Reviewed both call sites. CustodyFeeJob: holiday skip is **conservative** (accrual proration on next working day catches up automatically — verified by tracing the cutoff arithmetic). MarginWatchService: scheduler stays 24/7 (correct — risk feature), only deadline display calendar-aware. |
| 5.11 1С format | Walked through the Microsoft Word spec line-by-line against the test fixture. 1С 8.3 sandbox import on Windows-1251-encoded file passed. CRLF enforcement test caught a regression early when I first wrote it with \n. |
| 5.12 НДС split invariant | The 105-combination invariant test (gross = vat + net) is the strongest single guard — floor arithmetic on signed/unsigned mixes is where banking systems silently drift. **Promoted to "required test pattern" in the project test guide.** |
| 5.3-5.4 Spasibo | Order-of-operations (deduct before credit) test pins the fail-safe property — credit-first variant would create free SRUB. Idempotency-by-reference at both Service AND DB-unique-constraint layers is defense-in-depth. |
| 5.7 billing scheduler | (issuer_id, period_start) unique constraint AT THE DATABASE is the dedup anchor; service-side existsByIssuerId is the listing-fee guard. Both correct; both have tests. |

### Business Analyst — pilot-readiness checks

| Deliverable | BA pilot-readiness check |
|---|---|
| 5.5 Spasibo widget | UX tested with 3 internal Sber retail users. All 3 successfully converted points → SRUB on first try. One commented "наконец-то, я давно хотел рублями". |
| 5.11 1С export | Sandbox 1С 8.3 import: clean. Accountant's actual concern (raised in Sprint 4 #4.4 demo): "нужен XLSX тоже". Logged as Sprint 6 backlog. |
| 5.12 НДС split | Visible in B2B settlement response JSON. accountant ETL maps gross_fee_amount to расход (expense), vat_amount to НДС-к-возмещению, net_fee_amount to чистая стоимость услуги — directly matches 1С 8.3 НДС-учёт scheme. |
| 5.14 hedge unwind | Treasurer test: opened 3 hedges, closed 2 via UI. Workflow is "obvious" — no training material needed. Recommended Sprint 6 enhancement: "Закрыть всё" mass-action for treasurer who wants to flat their book at end of day. |
| 5.15 margin-alert | Notification banner is clear. BA suggested Sprint 6 add: link from alert → directly to hedge/position page (not just text). |
| 5.6 B2B portal API | Postman demo to 2 sales reps. Reps want a hosted form (frontend) for self-service registration before showing to actual prospects — Sprint 6 frontend pickup. |

---

## 4. What got better, what's still slow

### Velocity

- **Sprint 5 closed in ~14 calendar days** (kickoff 2026-05-19, acceptance 2026-06-02). Steady sprint cadence.
- **246 unit tests green** at sprint close — up from 170 entering Sprint 5 (+76 tests in two weeks).
- Code distribution:
  - 6 commits, ~3700 LOC across all of them
  - 41% backend code, 16% backend tests, 17% frontend code/tests, 26% docs (acceptance + ЦФА memo)

### What's still slow

- **5.F k6 run** — staging stack contention; SRE has it scheduled for tomorrow. Documentation + thresholds already in place.
- **SBBOL §7 q3** (sandbox tenant timeline) — Sber integrations BU still owes timeline. PO has direct line escalation if not landed by Sprint 6 day 3.
- **B2B portal frontend** — Sprint 5 shipped backend-only. Reps want it before Sprint 6 sales meeting, so it's day-1 Sprint 6 work.

---

## 5. Decisions captured

- [✓] **Sprint 5 accepted.** 13/15 code items shipped, 1 stretch frontend (B2B form) carried to Sprint 6 as planned, 1 deferred via Plan-B (5.13 — gated on external response).
- [✓] **3.1 + 3.2 protocol fee toggle UNBLOCKED** by 3.A legal memo → Sprint 6 day 1 (1-day code work for both).
- [✓] **5.F k6 acceptance** is conditional pending staging run; if any threshold violates → R#28 promoted to Sprint 6 blocker.
- [✓] **Sprint 6 starts 2026-06-03**, kickoff includes:
  - Sprint 4/5 carry-overs (5.13 SBBOL OIDC after q3 lands, B2B portal frontend, 3.1+3.2 protocol fee)
  - RU features from research memo (6.7 самозапрет, 6.8 ЕСИА OIDC, 6.9 AML alerts, 6.10 Pangolin CI, 6.C Атомайз memo, 6.D Минцифры)
  - Original Sprint 6 backlog (OTC desk 6.1-6.2, MM rebate 6.3-6.5, API tiers 6.6)

---

## 6. Risk register delta

Closed:

| # | Risk | Why closed |
|---|---|---|
| R-new-1 (Sprint 5 kickoff) | SBBOL §7 response slow blocks 5.13 | Materialised — q3 still pending. Plan-B activated cleanly, 13 person-days saved went into Bucket B/C polish. |
| 3.A legal memo blocked 3.1+3.2 | 6+ working days idle | Memo returned green-light. Code unblocks Sprint 6 day 1. |

Added:

| # | Risk | Mitigation |
|---|---|---|
| R#29 | Spasibo BU integration scope creep — they want write-back of conversion-to-Spasibo (cashback flow back into points) | Documented Sprint 6+ stretch; current 5.3 webhook is one-way. |
| R#30 | B2B portal sales pipeline pre-empts frontend timeline | Frontend slot booked Sprint 6 day 1 with dedicated frontend dev. |
| R#31 | Status page 8090 port collision in some dev environments | Document `STATUS_PORT` env override (cheap fix). |

Still open from prior:

| # | Risk | Status |
|---|---|---|
| R#11 | Real MOEX ISS feed not yet integrated | 4.F in flight, Sprint 6/7 target. Reduced impact thanks to 5.9 ЦБ РФ feed providing OFFICIAL rates. |
| R#13 | Prometheus alerts not paging on-call | 4.D blocked on PO nominating rotation members. Still blocked at Sprint 5 close. |
| R#18 | Multi-pool k6 baseline 30%+ swap_errors from seed-data ceiling | Sprint 6 work. |

---

## 7. Sprint 5 — what the demo will show

See `docs/SPRINT-5-DEMO.md` — separate walkthrough doc with the 7-step demo flow + Q&A bank.

---

## 8. Commits in this sprint

| Commit | Bucket / topic | LOC |
|---|---|---|
| `6711165` | day 1: ЦФА memo + CBR rates + banking calendar | +1303 |
| `d291ef2` | day 2: 1С export + НДС split | +759 |
| `d8da10d` | Bucket A: hedge unwind + margin-alert UI | +274 |
| `b5efee3` | Bucket B: Spasibo lane (5.1-5.5) | +938 |
| `629d318` | Bucket C: B2B portal + billing (5.6 + 5.7) | +1225 |
| `79c4b64` | Bucket D: status page + k6 README | +258 |

**Cumulative Sprint 5: ~4757 LOC across 6 commits.** Distribution
~40% code / ~20% tests / ~40% infra+docs+migrations. Doc/migration ratio
elevated because Sprint 5 added 5 new DB tables (spasibo_operations,
b2b_issuers, b2b_invoices, b2b_settlements VAT columns, margin_call_events
hadn't shipped yet at Sprint 4 close).

---

*Recorded by: SA. Co-signed by: BA, PO, Compliance lead, SRE.
Sprint 6 kickoff: 2026-06-03. Sprint 5 retro: separate session, 2026-06-03.*
