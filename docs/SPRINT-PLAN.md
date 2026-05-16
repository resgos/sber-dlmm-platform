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

## Sprint 5 — "SberSpasibo integration + DLMM-as-Service portal v1"

**Theme**: Big distribution lever (60M users via Spasibo) + start the
long-term B2B portal pillar.

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

### Non-code work

| # | Task | Source | Owner | Effort | Deadline |
|---|---|---|---|---|---|
| 5.A | **Spasibo BU integration scope** | M open Q#3 | PO + Loyalty product head | 3d | Sprint kick-off |
| 5.B | **Q3 OKR review** with PO + CFO — protocol fee revenue baseline vs target | M#1, M#6 outcomes | PO + CFO | 1w | Sprint mid |
| 5.C | **KYB (Know Your Business) workflow design** for B2B portal | M#11 dep | Compliance + UX | 1w | Sprint mid |

### Sprint 5 acceptance

- 1 Spasibo user successfully converts points → SRUB end-to-end
- B2B portal accepts 1 test issuer through full KYB workflow
- Billing engine generates first invoice (test mode)
- status.dlmm.sber-online.ru serving 200 OK

---

## Sprint 6 — "OTC desk + MM rebate program"

**Theme**: Institutional layer. OTC for block trades. Market-maker
rebate makes the platform attractive to whales.

### Code work

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| 6.1 | **OTC desk admin workflow** — manual RFQ entry, principal-side execution | M#7 | Backend + Admin UI | 6d |
| 6.2 | **RFQ (request-for-quote) API** — POST /api/v1/rfq, GET /api/v1/rfq/:id for VIP clients | M#7 | Backend lead | 4d |
| 6.3 | **MM rebate scheduler** — daily job, top-10 LP by share, distributes 80% of protocol_fee_x/y | M#10 | Backend lead | 4d |
| 6.4 | **MM tier table** (Bronze/Silver/Gold) with config-driven rebate % | M#10 | Backend lead | 2d |
| 6.5 | **MM onboarding workflow** — admin approves MM, assigns tier, generates rebate report | M#10 | Backend + Admin UI | 4d |
| 6.6 | **API access paid tiers** — rate-limit per API key tier (Free/Pro/Enterprise) at gateway | M#20 (low priority but bundled) | SRE + Backend | 3d |

### Non-code work

| # | Task | Source | Owner | Effort | Deadline |
|---|---|---|---|---|---|
| 6.A | **First MM contracts signed** (2–3 anchor MMs) | M#10 dep | PO + Legal | 2w | End of sprint |
| 6.B | **OTC client onboarding** — first 3 institutional clients for OTC desk | M#7 dep | PO + Corp Sales | 2w | End of sprint |

### Sprint 6 acceptance

- 1 OTC block trade settled (target: ≥ 10M ₽ notional)
- 1 MM receives first rebate payment via scheduler
- Daily revenue dashboard shows protocol_fee + MM rebate distribution

---

## Sprint 7 — "Money market + index funds prep"

**Theme**: Open the LATER horizon. Tokenized money market is the
adjacent product unlock; index funds give retail something to buy.

### Code work

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| 7.1 | **Tokenized money market fund** — new YSRUB token, daily yield distribution via outbox event | M#9 | Backend lead | 8d |
| 7.2 | **Yield distribution engine** — accrue from idle treasury overnight deposit + spread | M#9 dep | Backend lead | 5d |
| 7.3 | **Index basket token (SBER10 first)** — token represents 10% each of SBER, GAZP, LKOH, GMKN, ROSN, MGNT, YNDX, TATN, NLMK, VTBR | M#15 | Backend lead | 5d |
| 7.4 | **Index rebalance scheduler** — daily check, rebalance when drift > 2% from target weights | M#15 dep | Backend lead | 4d |
| 7.5 | **Index dashboard on user-ui** — show composition, NAV, performance vs MOEX | M#15 dep | Frontend | 4d |
| 7.6 | **Reserve health for YSRUB** — daily attestation that backing assets cover circulating supply | M#9 dep | Backend + Compliance | 3d |

### Sprint 7 acceptance

- YSRUB mint/burn cycle works end-to-end with daily yield credited
- SBER10 basket token tradeable in dedicated pool
- Daily rebalance produces correct allocations
- Reserve attestation surfaces on public status page

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

---

## Cumulative revenue model (refresher from MONETIZATION-STRATEGY)

| Quarter | Stack at end-of-quarter | Run-rate (₽/year) |
|---|---|---:|
| Q3 2026 (Sprint 3-4 close) | protocol fee + exit + custody + Treasury LP + FX hedge pilot + B2B settlement v1 + sponsored pools | **300-500M** |
| Q4 2026 (Sprint 5-6 close) | + SberSpasibo + OTC desk + MM rebate + DLMM-as-Service (3-5 issuers) | **800M-1.2B** |
| Q1 2027 (Sprint 7+) | + Money market + index funds | **1.5-2B** |
| Q2 2027+ | + Tokenized bonds pilot (if Q4 legal review goes well) | **3-4B** |

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

*Last updated: 2026-05-16 (post BA discovery + monetization). Owner: IT-lead.
Update at every sprint close.*
