# Sber DLMM — Sprint Plan (post Sprint 2)

**Author**: IT-lead, after BA discovery + monetization strategy.
**Status**: Sprint 3 ready to start. Sprints 4–7 firm scope, dates indicative.
**Window**: each sprint = 2 weeks.

> Sources legend in task tables:
> - **R#N** → risk from `docs/RISK-REGISTER.md`
> - **M#N** → idea from `docs/MONETIZATION-STRATEGY.md`
> - **D#N** → commitment from `docs/PRODUCT-DISCOVERY-2026-05-16.md`
> - **TD** → carry-over technical debt (Sprint 1/2 follow-ups)

---

## Sprint 3 — "Revenue activation + tech-debt cleanup"

**Theme**: Stop gifting fees to LPs (closes the 0-revenue gap), close
the critical Liquibase risk, ship pre-demo deliverables.

**Capacity assumption**: 3 backend devs + 1 frontend + 1 SRE + 1 SA.
Roughly 8 person-days per task slot, ~6 slots in the sprint.

### Code work

| # | Task | Source | Owner | Effort | Notes |
|---|---|---|---|---|---|
| 3.1 | **Enable `protocol_fee_pct = 5%` per pool** + admin UI to tune | M#1 | Backend lead | 2d | Conditional on legal memo (see 3.A). Default 0 in code; admin flips per pool. |
| 3.2 | **Protocol fee distribution split** (LP gets 95%, protocol 5%) in pool fee accounting | M#1 | Backend lead | 2d | `total_protocol_fee_x/y` columns on `liquidity_pools`; sweep job to admin treasury account |
| 3.3 | **Custody fee (5 bps p.a. on user balances)** — scheduled accrual job | M#6 | Backend lead | 3d | Daily cron, accrual on `user_balances.available`, debit to admin treasury account |
| 3.4 | **Exit fee 10 bps on close LP position** | M#8 | Backend lead | 0.5d | In `LiquidityService.removeLiquidity`, deduct from withdrawn amount, route to protocol |
| 3.5 | **Real SRUB/CNY price oracle** — replace stub with MOEX-feed cron pull | R#11, D#3, M#3 dep | SA + Backend | 4d | Required for FX hedge demo. MOEX has open spot API; cache in price-oracle service |
| 3.6 | **Hikari pool 20 → 50** per service + connection-timeout tune | R#13, TD k6 | SRE | 1d | yaml per service. Re-baseline (3.7) measures impact |
| 3.7 | **k6 re-baseline** with multi-user mix + multi-pool rotation | R#18, TD | SRE | 2d | Rewrite `baseline.js` to rotate across 3 users × 4 pools; commit numbers to `loadtest/README.md` |
| 3.8 | **Liquibase migration split** — remove `CREATE TABLE` from `init-db.sql`, move to per-service changesets | R#4 (critical) | Backend lead | 4d | Tactical hack from Sprint 1 was MARK_RAN — proper split unblocks all future schema work |
| 3.9 | **Outbox extraction to `dlmm-common`** + entity scan auto-config | TD | Backend lead | 3d | Eliminates the 2-class duplication between pool-engine and token-service |
| 3.10 | **Same-pool row lock analysis** — measure under k6 + recommendation memo | TD, R#dep MM | SA | 2d | Doc only. Recommends optimistic locking vs per-bin granularity vs accept-and-shard. Blocks Sprint 6 MM rebate. |
| 3.11 | **Prometheus alert rules** — Hikari pending > 0 for 1m, outbox unpublished > 100, kafka lag > 1000 | R#13 (partial) | SRE | 2d | `docker/prometheus/rules/*.yml`. Pager not yet wired (Sprint 4). |
| 3.12 | **Gateway `/actuator/prometheus`** — wire webflux-style actuator | TD Sprint 2 | SRE | 1d | Closes the 1 DOWN target in Prometheus. |

### Non-code work (cross-functional)

| # | Task | Source | Owner | Effort | Deadline |
|---|---|---|---|---|---|
| 3.A | **Legal memo**: does `protocol_fee_pct > 0` flip our reg-frame from "internal clearing" to "broker-dealer"? | M open Q#1 | Compliance lead | 1w | Day 5 of Sprint 3 (blocks 3.1 activation) |
| 3.B | **Sber Treasury pitch deck** + meeting request | M#2 | PO + CFO | 1w | Day 7 (target Treasury meeting in Sprint 4) |
| 3.C | **B2B portal design sketch** (Figma + flows, no code) | M#11, D#3 | UX + BA | 5d | End of sprint |
| 3.D | **USE-CASE-FX-HEDGE.md** + `scripts/demo-fx-hedge.ps1` | D demo prep | SA | 2d | Day 10 |
| 3.E | **PO slide "FX hedge vs dealer desk"** with corp-sales numbers | D demo prep | PO | 3d | End of sprint |
| 3.F | **Compliance memo**: B2B settlement as "transfer" vs "swap" reg-categories | M open Q#5 | Compliance | 1w | End of sprint (blocks Sprint 4 #4.6) |

### Sprint 3 acceptance

- `protocol_fee_pct` toggleable in admin UI; pool fee revenue starts accruing (test pool seeded with 5%)
- Custody fee accrued daily on test balance; visible in admin dashboard
- SRUB/CNY real-time price visible in admin tokens page
- k6 re-baseline shows ≥3× improvement after Hikari bump (target: 100 RPS sustained, swap p99 < 1s)
- Liquibase changeset successfully runs on fresh DB + existing DB (validate-on-migrate)
- Gateway target UP in Prometheus
- All 4 demo-prep artefacts (3.D, 3.E, plus the 2 from previous discovery) ready
- Legal + compliance memos resolved

### Sprint 3 risks / dependencies

- 3.1 blocked by 3.A (legal). If memo says "yes, becomes broker-dealer" — defer 3.1 to Sprint 4 + add broker-dealer license track as separate spike.
- 3.5 dependent on MOEX feed API access (may need procurement).
- 3.D feasibility hinges on real SRUB/CNY oracle (3.5) — if 3.5 slips, demo falls back to seed prices with disclaimer.

---

## Sprint 4 — "FX hedges pilot + B2B settlement v1"

**Theme**: Land first corp-facing product (FX hedges). Stand up the B2B
settlement rail prototype. Promote CI gates from advisory to required.

### Code work

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| 4.1 | **FX hedge corp UI** on user-ui — hedge calculator, quote, execute | M#3 | Frontend | 5d |
| 4.2 | **Counterparty exposure limits** in pool-engine (per-user max nominal) | M#3 dep | Backend lead | 4d |
| 4.3 | **Margin-call basic logic** for large positions (notify user @ 80% of limit) | M#3 dep | Backend lead | 3d |
| 4.4 | **Settlement report API** — GET /api/v1/transactions/report?userId&from&to (CSV) | M#3 dep | Backend lead | 2d |
| 4.5 | **SBBOL integration design sketch** (no code) | M#3 dep | SA + Sber integrations BU | 5d |
| 4.6 | **B2B settlement endpoint prototype** — POST /api/v1/transfers/b2b (P2P, fee=5bps) | M#4 | Backend lead | 5d |
| 4.7 | **Same-pool row lock fix** — per memo from 3.10 (likely optimistic locking) | TD (3.10 output) | Backend lead | 5d |
| 4.8 | **Container scan → required status check** on PR | Sprint 2 carry-over | SRE | 1d |
| 4.9 | **Sponsored pool placement** — admin can pin pool to top of /pools listing | M#12 | Backend + Frontend | 3d |

### Non-code work

| # | Task | Source | Owner | Effort | Deadline |
|---|---|---|---|---|---|
| 4.A | **Sber Treasury onboarding** — first 100M ₽ pilot LP placed | M#2 | PO + Treasury BU + Backend (operational support) | 2w | End of sprint |
| 4.B | **Pilot client selection** — 3-5 corp clients for FX hedge pilot | M#3 | PO + Corp Sales | 1w | Sprint mid |
| 4.C | **SBBOL contract** — start integration negotiation | M#3 dep | PO + Sber integrations | 2w | End of sprint |
| 4.D | **PagerDuty / Alertmanager wiring** — Prometheus rules → on-call rotation | R#13 final close | SRE | 3d | End of sprint |

### Sprint 4 acceptance

- 1+ corp client onboarded to FX hedge with at least 1 settled hedge
- Sber Treasury active as LP with measurable contribution to TVL
- B2B settlement endpoint passes integration test with mock corp account
- Same-pool row lock fix shows linear scaling under k6 (multi-VU same-pool test)
- Prometheus alerts fire to PagerDuty in staging

---

## Sprint 5 — "SberSpasibo + DLMM-as-Service portal v1 + RU-market fit"

**Theme**: Big distribution lever (60M users via Spasibo), start the
long-term B2B portal pillar, **and lock the RU-market-specific
foundations before pilot scale-up (see `docs/RU-MARKET-RESEARCH-2026-05-18.md`).**

### Code work

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| 5.1 | **SSPAS token** — new entry in `tokens` table, mint authority = Spasibo BU | M#5 | Backend lead | 2d |
| 5.2 | **SSPAS/SRUB pool** — seed with initial liquidity from Spasibo treasury | M#5 | Backend lead | 1d |
| 5.3 | **SberID → SSPAS bridge** — webhook from Spasibo "user earned N points" → mint SSPAS to user balance | M#5 | Backend + Sber loyalty BU | 8d |
| 5.4 | **Loyalty conversion API** — POST /api/v1/spasibo/convert (SSPAS → SRUB swap, fee 0 bps for retail flow) | M#5 | Backend lead | 3d |
| 5.5 | **Spasibo conversion widget** in user-ui (1-click "конвертировать Спасибо в рубли") | M#5 | Frontend | 4d |
| 5.6 | **B2B portal MVP** — issuer registration, KYB workflow, token-creation form | M#11, D#3 | Frontend + Backend | 8d |
| 5.7 | **Billing engine for B2B tier** — listing fee (one-time) + monthly retainer + volume % | M#11 dep | Backend lead | 5d |
| 5.8 | **Public status page** at status.dlmm.sber-online.ru (read-only mirror of `/actuator/health`) | M parking | SRE + Frontend | 4d |
| 5.9 | **ЦБ РФ official rates feed** — `dlmm-price-oracle` connector pulls daily RUB↔USD/EUR/CNY from cbr.ru, caches 24h, dashboard tile "market vs official spread" | RU-M1 | Backend + Frontend | 2-3d |
| 5.10 | **Российский банковский календарь** — holidays table + `BankingCalendarService.isWorkingDay(date)`; wire into custody-fee accrual (#3.3 follow-up) and margin-call timing (#4.3 follow-up); user-ui shows "T+N рабочих дней" correctly | RU-D1 | Backend | 2-3d |
| 5.11 | **1С банк-клиент XML export** — extend `/api/v1/transactions/report` (Sprint 4 #4.4) with `format=1c-xml` returning 1С банк-клиент v3.0-compatible XML | RU-T3 | Backend | 3-4d |
| 5.12 | **НДС split on B2B fees** — Sprint 4 #4.6 `b2b_settlements` gains `vat_amount` column; fee config splits 20%/льготная; CSV/XML export shows breakdown | RU-T2 | Backend lead | 2d |

### Non-code work

| # | Task | Source | Owner | Effort | Deadline |
|---|---|---|---|---|---|
| 5.A | **Spasibo BU integration scope** | M open Q#3 | PO + Loyalty product head | 3d | Sprint kick-off |
| 5.B | **Q3 OKR review** with PO + CFO — protocol fee revenue baseline vs target | M#1, M#6 outcomes | PO + CFO | 1w | Sprint mid |
| 5.C | **KYB (Know Your Business) workflow design** for B2B portal | M#11 dep | Compliance + UX | 1w | Sprint mid |
| 5.D | **259-ФЗ ЦФА classification memo** — are DLMM tokens "цифровые финансовые активы"? Determines whether ЦБ оператор ЦФА registration applies (huge lift) or we stay in "internal clearing" frame. Blocks RU-C2, RU-C3, RU-P1 (SBP rail reg-frame depends). | RU-R5 | SA + Compliance | 5d | Sprint mid |
| 5.E | **SBBOL §7 questions submission** (carry-over from Sprint 4 #4.5) — formal letter to Sber integrations BU with the 6 blocking + 3 non-blocking questions from `docs/SBBOL-INTEGRATION-DESIGN.md` §7. Gates Sprint 5 SBBOL code (item 5.13 below). | Sprint 4 #4.5 | PO | 3d | Sprint kick-off |
| 5.F | **k6 single-pool re-baseline** (carry-over from Sprint 4 #4.7 acceptance) — verifies optimistic-lock retry loop scales linearly under contention. Required before Treasury pilot (#4.A) goes live. | Sprint 4 acceptance | SRE | 2d | Sprint mid |

**Sprint 5 carry-overs from Sprint 4 (added at acceptance):**

| # | Task | Owner | Effort |
|---|---|---|---|
| 5.13 | **SBBOL OIDC handoff (read-only first cut)** — gateway + user-service code, balance lookup via new SbbolClient in token-service. Gated on 5.E response landing | Backend | 8d |
| 5.14 | **Hedge unwind UI** on user-ui — close-position flow for FX hedges executed via Sprint 4 #4.1 page. Currently treasurer must use generic Swap | Frontend | 1-2d |
| 5.15 | **Margin-alert rendering** on user-ui — consumer of `MARGIN_WARNING` / `MARGIN_CALL` events (Sprint 4 #4.3) renders the alert in the notification panel | Frontend | 2d |

### Sprint 5 acceptance

- 1 Spasibo user successfully converts points → SRUB end-to-end
- B2B portal accepts 1 test issuer through full KYB workflow
- Billing engine generates first invoice (test mode)
- status.dlmm.sber-online.ru serving 200 OK
- **ЦБ РФ rates spread tile live on admin dashboard**
- **Custody fee accrual respects RU banking calendar** (test: holiday day, no accrual)
- **CSV report endpoint accepts `format=1c-xml`** and the output imports cleanly in 1С банк-клиент v3.0
- **B2B settlement response splits VAT** from gross fee
- **259-ФЗ memo accepted by Compliance** with explicit verdict ("ЦФА / not ЦФА / borderline")
- **k6 single-pool baseline shows linear scaling** (no >5% retry-fail rate at 100 VU same-pool)
- **SBBOL OIDC handoff handles silent SSO** for a test sandbox tenant (read-only)

---

## Sprint 6 — "Compliance core + carry-overs + B2B portal frontend"

**Theme rebalanced 2026-06-02 post Sprint 5 acceptance** (was: "OTC desk
+ MM rebate + RU compliance core"). Sprint 5 over-shipped on the Spasibo
+ B2B portal lanes and now has 5 day-1 commitments + the carry-overs to
absorb. **OTC desk + MM rebate moved to Sprint 7** to keep Sprint 6
under capacity — see `## Sprint 7` revised section below.

> Sprint 5 acceptance §5: «Sprint 6 day-1 includes 3.1+3.2 (1d each
> unblocked), 5.13 SBBOL OIDC (after §7 q3), B2B portal frontend
> (sales pre-empted by R#30). Plan-B: OTC desk легко переносится —
> рынок институциональный, может ждать». Decision: rebalance.

### Code work (post-rebalance)

**Day-1 commits (5 items, ~14 person-days):**

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| **3.1** | **Activate `protocol_fee_pct = 5%` per pool** + admin endpoint to tune (unblocked by 3.A legal memo) | M#1, was Sprint 3 | Backend lead | 1d |
| **3.2** | **Protocol fee distribution split** (LP 95% / protocol 5%) — wire `total_protocol_fee_x/y` accumulation in pool fee path | M#1, was Sprint 3 | Backend lead | 1d |
| **5.F-run** | k6 single-pool re-baseline run on staging (verify Sprint 4 #4.7) | Sprint 5 carry | SRE | 0.5d |
| **5.13** | SBBOL OIDC handoff — Plan-B unblock once §7 q3 lands (sandbox tenant timeline) | Sprint 5 Plan-B | Backend | 5-8d |
| **5.6-FE** | **B2B portal frontend** — self-service issuer registration form + admin KYB review screen (sales pre-empted) | Sprint 5 #5.6 carry | Frontend | 4d |

**RU compliance core (~14 person-days, kept from original Sprint 6):**

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| 6.7 | **Самозапрет (115-ФЗ amendment 2024)** — `user_self_restrictions` table, user-ui «Запретить себе новые позиции» toggle, swap/hedge/add-liquidity check | RU-R6 | Backend + Frontend | 3-4d |
| 6.8 | **ЕСИА (Госуслуги) OIDC handoff** — second OIDC provider on the auth bus (depends on 5.13 bus landing first) | RU-I1 | Backend | 5-6d |
| 6.9 | **AML pattern-detection alert (proactive)** — scheduled scan: round-amount repeats / fast-in-fast-out / sub-600k splits → `aml_alerts` + compliance email | RU-U3 | Backend | 3-4d |
| 6.10 | **Pangolin / PgPro CI matrix test** — backend.yml runs IT against postgres:16 AND pangolin:latest | RU-X2 | SRE | 1-2d |

**BA-flagged Sprint 5 acceptance enhancements (~3 person-days):**

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| 6.14 | Hedge **«Закрыть всё»** mass-action on HedgePage (treasurer flat-the-book) | BA Sprint 5 §3 | Frontend | 1d |
| 6.15 | Margin-alert **deep-link** «перейти к позиции» from NotificationBell | BA Sprint 5 §3 | Frontend | 0.5d |
| 6.16 | Spasibo BU write-back (cashback flow back into points) — design memo only, R#29 | BA + SA | SA | 1.5d |

**Total Sprint 6: ~31 person-days** (vs 30 capacity = ~3% over, healthy).

### Moved to Sprint 7 (decision 2026-06-02)

| # | Was Sprint 6 | Reason for move |
|---|---|---|
| **6.1** OTC desk admin workflow | 6d | Institutional client onboarding is multi-month; OTC can ship one sprint later without losing pipeline |
| **6.2** RFQ API | 4d | Pairs with 6.1 |
| **6.3** MM rebate scheduler | 4d | Depends on 3.1+3.2 (this sprint) — clean to ship together with MM tiers |
| **6.4** MM tier table | 2d | Pairs with 6.3 |
| **6.5** MM onboarding workflow | 4d | Pairs with 6.3+6.4 |
| **6.6** API access paid tiers | 3d | Independent; bundles naturally with MM tiers Sprint 7 |

**Net Sprint 6 cut: 23 person-days saved** by moving OTC+MM block to Sprint 7.

### Non-code work (Sprint 6)

| # | Task | Source | Owner | Effort | Deadline |
|---|---|---|---|---|---|
| 6.A | **First MM contracts signed** (2–3 anchor MMs) — *prep for Sprint 7 #6.3-6.5* | M#10 dep | PO + Legal | 2w | End of sprint |
| 6.B | **OTC client onboarding** — first 3 institutional clients — *prep for Sprint 7 #6.1-6.2* | M#7 dep | PO + Corp Sales | 2w | End of sprint |
| 6.C | **Атомайз / Мастерчейн listing discovery memo** | RU-C2 | SA | 5d | Sprint mid |
| 6.D | **Минцифры реестр application phase 1 form prep** | RU-X4 | PO + Legal | 2w | End of sprint |
| 6.E | **SBBOL §7 q3 response chase** — escalate to Sber integrations leadership if not landed by Day 3 | Sprint 5 carry | PO | Day 3 | Day 3 |

### Sprint 6 acceptance (post-rebalance)

Day-1 unblocks:
- **protocol_fee_pct=5% live** on at least 1 test pool (3.1+3.2 verified end-to-end with admin tooling)
- **k6 single-pool re-baseline** numbers attached to commit, swap_errors <5% confirmed
- **SBBOL OIDC sandbox** silent SSO works (5.13 once §7 q3 lands)
- **B2B portal frontend** registration form + admin KYB review screen live

RU compliance core:
- **Самозапрет toggle** blocks swap end-to-end (test: enable → 403 USER_SELF_RESTRICTED)
- **ЕСИА login** completes silent SSO from Госуслуги test environment
- **AML pattern alert** fires on synthetic split-amount sequence
- **CI matrix green** on Postgres 16 + Pangolin

Cross-functional:
- **Атомайз/Мастерчейн memo** accepted with Q4 go/no-go verdict
- **Минцифры phase 1 form** submitted
- 2-3 MM contract drafts in legal review (prep for Sprint 7)
- 3 OTC institutional clients in onboarding pipeline (prep for Sprint 7)

---

## Sprint 7 — "OTC + MM rebate (cut-over) + Money market + index funds"

**Theme rebalanced 2026-06-02** — picks up the OTC + MM rebate block
moved out of Sprint 6 (capacity over-flow) and the original Sprint 7
money-market + index-funds backlog. Cross-functional MM/OTC contracts
ripen during Sprint 6 so code lands ready-for-go in Sprint 7.

> **Re-rebalanced 2026-06-17** post Sprint 6 acceptance — 3 carry-overs
> (5.13 + 5.6-FE + 6.8 = 17d) + revenue T1 picks (~3d code) + UX Critical
> block (~4-5d) overflow 30d capacity at +80%. OTC (6.1-6.2) + remaining
> MM (6.6) + money market (7.1-7.2) + index funds (7.3-7.6) move into
> Sprint 8/9. See `docs/SPRINT-7-KICKOFF.md` for full daily plan.

### Code work — Sprint 7 actual (after second rebalance)

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| **5.13** | SBBOL OIDC handoff — stub-against-defaults (per SBBOL-INTEGRATION-DESIGN §2.3) | Sprint 6 carry | Backend | 8d |
| **5.6-FE** | B2B portal frontend (D-01 mockups in hand) | Sprint 5/6 carry | Frontend | 4d |
| **6.8** | ЕСИА (Госуслуги) OIDC — second provider on shared bus | Sprint 6 carry | Backend | 5-6d |
| **6.3** | MM rebate scheduler (was Sprint 6 originally) | M#10 | Backend | 4d |
| **6.4** | MM tier table (Bronze/Silver/Gold) | M#10 | Backend | 2d |
| **6.5** | MM onboarding workflow + admin endpoints | M#10 | Backend + Admin UI | 4d |
| **R-d** | B2B integration fee on KYB-approve | Revenue research | Backend | 2d |
| **R-m** | NDS transparency badge | Revenue research | Frontend | 1d |
| **UX Critical block** | UX-003 + UX-007 + UX-016 + UX-037 + UX-042 + UX-008 (7 items) | UX-REVIEW | Frontend (+ backend for UX-008) | 4-5d |
| **R-UX-035** | SelfRestrictionPanel "Cancel set" undo | Sprint 6 BA | Frontend | 3h |
| **R-UX-036** | PositionsPage `?highlight=` param consumption | Sprint 6 BA | Frontend | 1h |
| **R-Pangolin-1** | Pangolin 1.5 BIGINT InvariantTest divergence | Sprint 6 SRE | SRE | 0.5d |

**Total Sprint 7: ~35 person-days vs 30 capacity = ~17% over.** Plan-B in
kickoff §8: UX Critical block items are individually small (0.5-1d each),
cut last 2 if needed.

### Sprint 7 acceptance criteria

See `docs/SPRINT-7-KICKOFF.md` §7 for full checklist.

---

## Sprint 8 — "UX Hardening Sprint" (rebalanced 2026-06-23 per AU-1)

**Theme**: One-sprint pause on the commercial roadmap to close the
critical gap surfaced by the system audit (`docs/SYSTEM-AUDIT-2026-06-17.md`):
**backend quality 9/10, frontend quality 6/10**. Sprint 7 mid-rebalance
absorbed the first 3.5d of safety-rail work (dedup, e2e, test honesty);
Sprint 8 makes the rest systemic.

> **AU-1 decision (PO + IT-lead, 2026-06-23)**: Option B — "UX Hardening
> Sprint 8" — adopted over Option A (status quo commercial). Trade-off
> noted in audit §6: ~1-month delay on ~150M ₽/yr of OTC + money market
> features. Designer's prior verdict ("frontend execution lags badly")
> + Sprint 7 mid-rebalance absorption confirm UX investment now is
> the right call.
>
> Full daily plan: `docs/SPRINT-8-KICKOFF.md`.

### Code work — backlog (~28 person-days vs 27d realised-capacity ceiling)

**Sprint 7 carry-overs (~2.5d):**
- UX-042 SwapPage mobile breakpoint fix (1d, Frontend)
- UX-016 Hedge unwind quote language fix (0.5d, Frontend)
- R-Pangolin-1 BIGINT InvariantTest divergence (0.5d, SRE)
- R-UX-035 SelfRestrictionPanel "Cancel set" undo (0.5d, Frontend)

**Security / audit-track (~5.5d):**
- **AU-2** Stylelint `color-no-hex` pre-commit hook (0.5d, SRE+FE)
- **AU-3** JWT revocation: Redis denylist by `jti` + `/auth/logout` + filter check (2d, Backend dev 2)
- **AU-4** Admin audit log: `admin_audit_log` table + `@AdminAudit` AOP (3d, Backend lead)

**Test coverage (~5d):**
- **C-7** Admin-UI vitest setup + first 10 component tests (3d, Frontend)
- **C-9** SwapPage vitest coverage (slippage, flip, MAX, alerts, quote refresh) (2d, Frontend)

**Designer pass + accessibility wave 1 (~5d):**
- **UX-DS-1** Design tokens audit + top-10 inline-style sweep (2d, Designer+FE)
- **UX-A11Y-1** Accessibility wave: 20+ aria + semantic HTML on Dashboard/Swap/Pools/Login (2d, FE)
- **UX-MOBILE-1** Mobile sweep — `@media` for Dashboard/Pools/Positions (1d, FE)

**i18n foundation (~3d):**
- **C-4** `react-i18next` setup + RU extraction from top 5 pages (3d, FE)

**UX-Major block first wave (~5d):**
- M-2 LoginPage "Forgot password?" + stub reset flow (1.5d, BE+FE)
- M-3 RegisterPage 2-step wizard (1.5d, FE)
- M-4 Dashboard stat tiles → drill-down (1d, FE)
- M-5 PoolsPage filter+sort (1d, FE)

**SRE / observability (~2d):**
- **AU-8** RISK-REGISTER.md refresh (1d, SA)
- **C-10** Resilience4j wiring on fee-service + admin-bff + transaction-service (1d, BE dev 3)

**Sprint 8 acceptance criteria** (preview — full list in kickoff):
- Stylelint pre-commit blocks new `#hex` in `.tsx`
- JWT revocation: logout adds `jti` to Redis; revoked token → 401
- Admin audit log captures last 5 admin mutations
- Admin-UI has first 10 vitest cases green in CI
- aria-label count ≥ 20 across user-ui pages (grep-verified)
- Dashboard / Pools / Positions render at 320px width
- `locales/ru.json` extracts top 5 pages' Cyrillic strings

### Deferred from Sprint 8 to Sprint 9 (commercial backlog slide)

Per AU-1 trade-off:
- 6.1 OTC desk admin workflow (6d) → Sprint 9
- 6.2 RFQ API for VIP clients (4d) → Sprint 9
- 6.6 API access paid tiers (3d) → Sprint 9
- R-M-33 Public Data API tiers (3d) → Sprint 9
- 7.1 Tokenized money market YSRUB (8d) → Sprint 9-10
- 7.2 Yield distribution engine (5d) → Sprint 9-10
- R-h CBR spread alert subscription (4d) → Sprint 10
- 7.6 YSRUB reserve health attestation (3d) → Sprint 10

---

## Sprint 9 — "OTC desk + RFQ + money market + API tiers" (shifted from Sprint 8 per AU-1)

**Theme** (rebalanced 2026-06-23): the commercial backlog originally
planned for Sprint 8 — institutional layer + tokenized money market
launch + Public Data API tiers — slid one sprint to make room for the
UX Hardening Sprint. Index funds + 152-ФЗ + Spasibo write-back further
slid to Sprint 10.

### Code work — initial backlog (~31 person-days vs 27 ceiling = +15% over; cut
last 1-2 if velocity confirms ceiling)

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| 6.1 | OTC desk admin workflow | M#7, Sprint 6→7→8→9 carry | Backend + Admin UI | 6d |
| 6.2 | RFQ API for VIP clients | M#7 | Backend | 4d |
| 6.6 | API access paid tiers at gateway | M#20 | SRE + Backend | 3d |
| R-M-33 | Public Data API tiers (extension of 6.6) | Revenue research | Backend | 3d |
| 7.1 | Tokenized money market fund (YSRUB) — daily yield outbox | M#9 | Backend | 8d |
| 7.2 | Yield distribution engine (overnight + spread) | M#9 | Backend | 5d |
| Spasibo write-back impl | Per #6.16, Sprint 8 contract dep (8.C) | Backend | 8d (cut if 8.C slips) |

**Sprint 9 acceptance criteria** (preview):
- 1 OTC block trade settled (≥ 10M ₽ notional)
- YSRUB mint/burn cycle works end-to-end with daily yield credited
- API access tiers (Free/Pro/Enterprise) live at gateway
- Spasibo cashback first real credit (if 8.C lands)

---

## Sprint 10 — "Index funds + 152-ФЗ audit + reserve health + UX Major wave 2"

**Theme** (rebalanced 2026-06-23): retail-side index basket + start of
compliance battery cascade + close-out items from money-market launch.

### Code work — initial backlog (~28 person-days)

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| 7.3 | Index basket token (SBER10) | M#15 | Backend | 5d |
| 7.4 | Index rebalance scheduler (drift > 2%) | M#15 | Backend | 4d |
| 7.5 | Index dashboard on user-ui | M#15 | Frontend | 4d |
| 7.6 | YSRUB reserve health attestation (carried from Sprint 8) | M#9 | Backend + Compliance | 3d |
| R-h | CBR spread alert subscription | Revenue research | Backend | 4d |
| 7.X | 152-ФЗ ПДн audit + encryption-at-rest verification | RU-R1 | Backend + Compliance | 5d |
| UX Major block second wave | UX-REVIEW Sprint 8 row | Frontend | 4-5d |

**Sprint 10 acceptance criteria** (preview):
- SBER10 basket token tradeable in dedicated pool
- Daily rebalance produces correct allocations
- 152-ФЗ audit checklist 80% green
- YSRUB reserve health daily attestation visible in admin dashboard

---

---

## Parking lot (icebox — not in any sprint yet)

Each line gets revisited at Q3 close.

| Idea | Source | Why parked | Re-evaluation trigger |
|---|---|---|---|
| **Tokenized bonds** (sovereign + corp) | M#14 | Needs legal lift (new token class, depositary, НКО account); 12+ months | Q4 2026 legal review |
| **Collateralized lending (Aave-style)** | M#13 | Depends on margin infra + reliable oracles; high blowup risk | After Sprint 7 money-market is stable |
| **Derivatives (options/futures)** | M#19 | 18+ months, large regulatory lift | 2027 H2 |
| **White-label DLMM to other banks** | M reject | Tinkoff/VTB won't buy from Sber; regional banks: TAM doesn't cover | Re-evaluate if Sber-as-issuer of fintech infra becomes a strategic priority |
| **Vault strategies (auto-rebalance)** | M#16 | Defer until money market is proven (Sprint 7 outcome dependency) | After Sprint 7 |
| **Embedded swap widget SDK** | M#17 | Distribution play; low priority until B2B portal traction proves it | After Sprint 5 portal launch |
| **Insurance pool (opt-in coverage)** | M#18 | Niche product, depends on insurance partner agreement | When 1st claim event happens (or anti-claim event) |
| **Social trading / copy LP** | BA parking | Retail acquisition play; revisit if MAU growth stalls | If MAU growth < 10% q-o-q |
| **DAO governance for pool params** | BA parking | Decentralization narrative; low revenue but high PR | Pre-IPO marketing window |
| **NFT auctions via DLMM bins** | M reject | Brand misfit | Never (rejected) |
| **Commit-reveal / privacy swaps** | M reject | AML/FATF Travel Rule incompatible | Never (rejected) |
| **Prediction markets** | M reject | RF betting law | Never (rejected) |
| **Tax-loss harvesting** | M reject | Tax-grey area in RF | Never (rejected) |
| **DLMM Academy / premium analytics** | M reject | Sub-50M TAM, not worth marketing cost | Never (rejected) |
| **ЕБС (Единая биометрическая система) integration** | RU-I3 | 6+ month integration cycle; low Sprint ROI vs ЕСИА (Sprint 6 #6.8) | When fully-remote KYB becomes business-critical (5+ corp pilots demand) |
| **ФНС API auto-filing for retail НДФЛ** | RU-T4 | Big regulatory lift; niche UX win | After Sprint 7 6-НДФЛ generation proves user demand |
| **ЭДО integration (Диадок / Контур / Тензор)** | RU-D2 | Per-vendor integrations, niche per-client | When ≥5 corp clients live AND ≥2 specifically request EДО bridge |
| **СберБизнес Эквайринг bridge** | RU-S3 | Speculative — corp merchant processing in DLMM context | When B2B portal (Sprint 5) shows merchant demand |
| **БРИКС Pay rail integration** | RU-M3 | API access uncertain in 2026 | When BRICS rail issues v1 spec |
| **Sber DFA platform integration** | RU-C3 | Own DFA platform GA + RU-R5 memo verdict required first | When Sber DFA platform reaches v1 + Sprint 5 #5.D memo accepts ЦФА classification |
| **Redis → Apache Ignite / KeyDB / Dragonfly migration** | RU-X1, IT-lead 2026-05-18 | **Demoted from Strategic tracks 2026-05-18** per IT-lead decision. Current Redis surface tiny (3 setIfAbsent in pool-engine + Spring Cloud Gateway RequestRateLimiter); ~14 person-days for zero business value without external trigger. KeyDB / Dragonfly = Redis-API drop-in (~0.5 day compose swap) — recommended as first-line defence if import-substitution mandate fires, before considering full Ignite migration. | Any of: (1) Sber Security mandates exit from Redis Inc.; (2) Ignite SQL Grid as 2-level cache becomes a quarter-strategy initiative; (3) production stack standardizes on Ignite for cluster-wide IMDG |

> Full BA discovery + scoring matrix → `docs/RU-MARKET-RESEARCH-2026-05-18.md`.

---

## Strategic tracks — not in any sprint, but actively monitored

Items here are **multi-sprint / multi-quarter** efforts that don't fit
the 2-week iteration. Each has a clear **activation trigger** — when
that fires, we cut Sprint-sized slices from the track and put them
on the regular backlog. Until then, IT-lead reviews status monthly.

> Redis → Ignite was previously listed here as Track 1, **demoted to
> the regular parking lot on 2026-05-18** by IT-lead — see parking-lot
> entry "Redis → Ignite / KeyDB / Dragonfly migration" for the full
> rationale + trigger preserved. Monthly monitoring dropped, Q3 review
> cycle picks it up like other parking items.

### Track 1 (was Track 2) — Postgres → Pangolin / Postgres Pro

**Source**: RU-X2 from `docs/RU-MARKET-RESEARCH-2026-05-18.md`.

**Sprint 6 slice landed** (#6.10 — CI matrix test). If Pangolin drop-in
holds, no migration is needed — we publish both images in Compose,
default to Postgres community, but document Pangolin-compatibility for
Минцифры реестр application (Track 3).

**Open task**: full performance baseline comparison after CI matrix
green. Sprint 7+ depending on Pangolin licensing terms with Sber.

### Track 2 (was Track 3) — Минцифры реестр отечественного ПО включение

**Source**: RU-X4 from `docs/RU-MARKET-RESEARCH-2026-05-18.md`.

**Status**: Sprint 6 #6.D starts form prep (phase 1 application).
Full process takes 4-9 months from submission to listing decision.

**Unlock when listed**:
- Госконтракт eligibility (госструктуры, гос-холдинги).
- Налоговая льгота: освобождение от НДС 20% на ПО.
- "Made in Russia" marketing badge.

**Dependencies** (must be true at submission):
- ≥80% codebase under Apache/MIT/BSD-compatible licenses ✓ (Java + Spring + most deps).
- БД из реестра (Postgres ОК; Pangolin даёт дополнительный bonus point) — Track 2 hedges this.
- DLMM not built on US-controlled SaaS dependencies (Redis Inc.-controlled → если Track 1 активируется, кладёт ещё balls в нашу сторону).
- Russian legal entity owns IP (Sber как legal entity OK).

### Track 3 (was Track 4) — 152-ФЗ / 115-ФЗ / 161-ФЗ compliance battery

**Source**: RU-R1, RU-R2, RU-R3, RU-R4 from `docs/RU-MARKET-RESEARCH-2026-05-18.md`.

**Status**: Sprint 5 #5.D ЦФА classification memo is the **root of this
dependency tree** — its verdict (ЦФА / not ЦФА / borderline) determines
how aggressive 161-ФЗ + СБП-rail compliance must be.

**Sprint 7+ slices to be cut after 5.D verdict**:
- 7.X — 152-ФЗ ПДн audit + encryption-at-rest verification (RU-R1)
- 7.X — 115-ФЗ Росфинмониторинг feed (RU-R2)
- 7.X — ЦБ Реестр финансовых платформ application (RU-R3, multi-quarter)

---

## Cumulative revenue model (refresher from MONETIZATION-STRATEGY)

| Quarter | Stack at end-of-quarter | Run-rate (₽/year) |
|---|---|---:|
| Q3 2026 (Sprint 3-4 close) | protocol fee + exit + custody + Treasury LP + FX hedge pilot + B2B settlement v1 + sponsored pools | **300-500M** |
| Q4 2026 (Sprint 5-7 close) | + SberSpasibo + MM rebate launched + DLMM-as-Service (3-5 issuers) | **700M-1.0B** ⤓ (Sprint 8 UX-Hardening trade) |
| Q1 2027 (Sprint 8-9 close) | + OTC desk + RFQ + API tiers + Money market (UX Hardening Sprint 8 pushed commercials by 1 sprint) | **1.3-1.8B** ⤓ |
| Q2 2027 (Sprint 10-11) | + Index funds + Spasibo write-back + reserve health attestation | **1.7-2.3B** |
| Q3 2027+ | + Tokenized bonds pilot (if Q4 legal review goes well) | **3-4B** |

> **Audit-induced AU-1 trade-off**: ~150-200M ₽/yr Q1 2027 dip vs original
> plan; compensated by reduced FE-bug rate post-hardening (measure at
> Sprint 9 retro). Net 2027 H2+ target unchanged.

---

## Cross-cutting tracking

**Owner**: PO. Weekly stand-up review of these 5 items, separate from sprint backlog:

1. **Legal memo on protocol fee** (3.A) — blocks 3.1. Need by Sprint 3 day 5.
2. **Treasury BU pitch outcome** (3.B / 4.A) — drives Sprint 4 revenue.
3. **Spasibo BU integration scope** (5.A) — drives Sprint 5 scope.
4. **Compliance on B2B settlement frame** (3.F) — blocks Sprint 4 #4.6.
5. **Same-pool row lock analysis output** (3.10) — blocks Sprint 6 MM rebate.

---

## What changed from "no plan" → this plan

Before this doc, Sprint 3 backlog had ~6 items (mostly tech-debt from
Sprint 2). After integrating BA's monetization strategy + discovery:

- **Sprint 3 grew from 6 → 12 code tasks + 6 non-code tasks** —
  added M#1, M#5 (oracle), M#6, M#8 to the existing TD slate.
  Acceptable because monetization tasks are small (most ≤ 3 days)
  and slot alongside the bigger Liquibase / outbox work.
- **Sprints 4–7 are entirely new** — built around the monetization
  pipeline. Previously the plan said "we'll figure out next quarter
  when we get there". Now there's a real ladder.
- **Parking lot exists for the first time** — explicitly catalogues
  the 14 ideas we're not building (with the trigger that would
  re-open the decision).
- **Cross-cutting items have owners** — legal, treasury, compliance,
  Spasibo BU calls aren't engineering tasks but they block
  engineering. Tracked outside the sprint.

---

*Last updated: 2026-06-23 (Sprint 7 mid-rebalance + Sprint 8 = "UX Hardening Sprint"
per audit AU-1). Owner: IT-lead. Update at every sprint close.*

**Changelog**
- 2026-05-16: initial plan (post BA discovery + monetization)
- 2026-06-02: Sprint 6 mid-rebalance (OTC + MM moved to Sprint 7)
- 2026-06-17: Sprint 7 kickoff post-second-rebalance; original carry-overs
- 2026-06-23: **Sprint 7 mid-rebalance** (3.5d safety-rail absorbed, 2.5d cut →
  Sprint 8); **Sprint 8 = "UX Hardening Sprint"** per AU-1; commercial backlog
  (OTC + MM + money market) slid Sprint 8 → 9; index funds + 152-ФЗ slid
  Sprint 9 → 10. See `SPRINT-7-MID-REBALANCE.md` + `SPRINT-8-KICKOFF.md`.
