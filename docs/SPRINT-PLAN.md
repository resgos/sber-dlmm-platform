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

## Sprint 6 — "OTC desk + MM rebate + RU compliance core"

**Theme**: Institutional layer (OTC + MM rebate to attract whales),
**plus the RU regulatory + retail-protection layer that lets us
serve non-SBBOL clients (ЕСИА) and meet 2024 user-protection law
(самозапрет, AML pattern alerts).**

### Code work

| # | Task | Source | Owner | Effort |
|---|---|---|---|---|
| 6.1 | **OTC desk admin workflow** — manual RFQ entry, principal-side execution | M#7 | Backend + Admin UI | 6d |
| 6.2 | **RFQ (request-for-quote) API** — POST /api/v1/rfq, GET /api/v1/rfq/:id for VIP clients | M#7 | Backend lead | 4d |
| 6.3 | **MM rebate scheduler** — daily job, top-10 LP by share, distributes 80% of protocol_fee_x/y | M#10 | Backend lead | 4d |
| 6.4 | **MM tier table** (Bronze/Silver/Gold) with config-driven rebate % | M#10 | Backend lead | 2d |
| 6.5 | **MM onboarding workflow** — admin approves MM, assigns tier, generates rebate report | M#10 | Backend + Admin UI | 4d |
| 6.6 | **API access paid tiers** — rate-limit per API key tier (Free/Pro/Enterprise) at gateway | M#20 (low priority but bundled) | SRE + Backend | 3d |
| 6.7 | **Самозапрет (115-ФЗ amendment 2024)** — `user_self_restrictions` table (immutable, append-only), user-ui "Запретить себе новые позиции" toggle in /profile, all open-position paths (swap/hedge/add-liquidity) check restriction. ЦБ РФ верификация stub for now (real CBR check in Sprint 7). | RU-R6 | Backend + Frontend | 3-4d |
| 6.8 | **ЕСИА (Госуслуги) OIDC handoff** — second OIDC provider beside SBBOL on the new `dlmm-common/auth/oidc` shared bus (Sprint 5 #5.13 introduces the bus). Maps ЕСИА claims to DLMM USER role; KYC=VERIFIED inherited from Госуслуги ESIA-VERIFIED status. | RU-I1 | Backend | 5-6d |
| 6.9 | **AML pattern-detection alert (proactive)** — scheduled job (every 15 min) scans recent transactions for: (a) round-amount repeats (≥3 transactions with identical amount within 1h), (b) fast-in-fast-out (deposit → withdrawal within 5 min, ≥80% of deposit), (c) split-transactions just under 600k ₽ threshold. Hits → `aml_alerts` table + email to compliance. **Pre-emptive to RU-R2 (Sprint 7 Росфинмониторинг feed).** | RU-U3 | Backend lead | 3-4d |
| 6.10 | **Pangolin / PgPro CI matrix test** — extend `.github/workflows/backend.yml` to run integration tests against both `postgres:16` and `pangolin/pangolin:latest`. Drop-in compatibility check; if all tests green, we can claim "Минцифры реестр-compliant БД stack" for Sprint 7+ application. | RU-X2 | SRE | 1-2d |

### Non-code work

| # | Task | Source | Owner | Effort | Deadline |
|---|---|---|---|---|---|
| 6.A | **First MM contracts signed** (2–3 anchor MMs) | M#10 dep | PO + Legal | 2w | End of sprint |
| 6.B | **OTC client onboarding** — first 3 institutional clients for OTC desk | M#7 dep | PO + Corp Sales | 2w | End of sprint |
| 6.C | **Атомайз / Мастерчейн listing discovery memo** — can DLMM list THEIR-issued ЦФА as tradeable assets? Opens new monetization (ЦФА secondary market fees, M-new). Conditional on Sprint 5 #5.D ЦФА memo lands "yes-but-bounded". | RU-C2 | SA | 5d | Sprint mid |
| 6.D | **Минцифры реестр application — phase 1 form prep** | RU-X4 | PO + Legal | 2w | End of sprint |

### Sprint 6 acceptance

- 1 OTC block trade settled (target: ≥ 10M ₽ notional)
- 1 MM receives first rebate payment via scheduler
- Daily revenue dashboard shows protocol_fee + MM rebate distribution
- **Самозапрет toggle blocks a swap end-to-end** (test: enable restriction → POST /swap → 403 with code USER_SELF_RESTRICTED)
- **ЕСИА login** completes silent SSO from Госуслуги test environment, DLMM JWT issued
- **AML pattern alert fires** for a synthetic split-amount sequence and appears in compliance inbox
- **CI matrix green on both Postgres 16 + Pangolin** (or Pangolin failures triaged + documented)
- **Атомайз/Мастерчейн memo accepted** with go/no-go verdict for Q4 ЦФА secondary market track

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
| **ЕБС (Единая биометрическая система) integration** | RU-I3 | 6+ month integration cycle; low Sprint ROI vs ЕСИА (Sprint 6 #6.8) | When fully-remote KYB becomes business-critical (5+ corp pilots demand) |
| **ФНС API auto-filing for retail НДФЛ** | RU-T4 | Big regulatory lift; niche UX win | After Sprint 7 6-НДФЛ generation proves user demand |
| **ЭДО integration (Диадок / Контур / Тензор)** | RU-D2 | Per-vendor integrations, niche per-client | When ≥5 corp clients live AND ≥2 specifically request EДО bridge |
| **СберБизнес Эквайринг bridge** | RU-S3 | Speculative — corp merchant processing in DLMM context | When B2B portal (Sprint 5) shows merchant demand |
| **БРИКС Pay rail integration** | RU-M3 | API access uncertain in 2026 | When BRICS rail issues v1 spec |
| **Sber DFA platform integration** | RU-C3 | Own DFA platform GA + RU-R5 memo verdict required first | When Sber DFA platform reaches v1 + Sprint 5 #5.D memo accepts ЦФА classification |

> Full BA discovery + scoring matrix → `docs/RU-MARKET-RESEARCH-2026-05-18.md`.

---

## Strategic tracks — not in any sprint, but actively monitored

Items here are **multi-sprint / multi-quarter** efforts that don't fit
the 2-week iteration. Each has a clear **activation trigger** — when
that fires, we cut Sprint-sized slices from the track and put them
on the regular backlog. Until then, IT-lead reviews status monthly.

### Track 1 — Redis → Apache Ignite (or KeyDB / Dragonfly) migration

**Source**: IT-lead inquiry 2026-05-18. Driven by potential
import-substitution mandate or expansion to Ignite SQL Grid for
hot-table caching.

**Current Redis surface (post Sprint 4)** — minimal:
- `dlmm-gateway` — Spring Cloud Gateway `RequestRateLimiter`
  (replenishRate=100, burstCapacity=150) on reactive Redis.
- `dlmm-pool-engine` — 3 sites in SwapService + LiquidityService
  using `StringRedisTemplate.opsForValue().setIfAbsent(...)` for
  idempotency markers with 24h TTL.
- Healthcheck + testcontainers.

**Effort estimate** (see standalone analysis):
- Pure cache (idempotency, healthcheck, tests) → ~3 days
- Gateway rate-limiter (via `bucket4j-ignite` distributed proxy)
  → +4 days
- Infra (Compose, Prometheus, port conflicts) → +2 days
- Buffer for new-stack debugging → +2 days
- **Total: ~11 person-days (~1 sprint slice for 1 backend + 0.5 SRE)**

**Activation triggers** (any of):
- 🇷🇺 **Mandate from Sber Security** to exit Redis Inc. dependency
  (import-substitution policy or sanctions risk classification).
- 🚀 **Ignite SQL Grid as 2-level cache** for hot Postgres tables
  (LP positions, user_balances) becomes a quarter-strategy initiative —
  Redis migration falls out as a side-effect.
- 📦 **Production environment** standardizes on Ignite for cluster-wide
  IMDG (separate platform decision).

**Risks if forced WITHOUT activation trigger**: ~14 person-days
spent for zero business value (current Redis usage is trivial and
production-proven). Backend dev time better spent on Sprint 5 SBBOL
OIDC handoff or compliance unblocks.

**Alternative**: Drop-in replacement with **KeyDB** or **Dragonfly** —
Redis-API-compatible Apache 2.0 forks, no code changes, sidesteps
vendor-lock concern without paying Ignite's IMDG complexity. ~0.5 day
of compose-file change + retest. Recommended **first line of defence**
if RU-X1 trigger fires.

### Track 2 — Postgres → Pangolin / Postgres Pro

**Source**: RU-X2 from `docs/RU-MARKET-RESEARCH-2026-05-18.md`.

**Sprint 6 slice landed** (#6.10 — CI matrix test). If Pangolin drop-in
holds, no migration is needed — we publish both images in Compose,
default to Postgres community, but document Pangolin-compatibility for
Минцифры реестр application (Track 3).

**Open task**: full performance baseline comparison after CI matrix
green. Sprint 7+ depending on Pangolin licensing terms with Sber.

### Track 3 — Минцифры реестр отечественного ПО включение

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

### Track 4 — 152-ФЗ / 115-ФЗ / 161-ФЗ compliance battery

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
