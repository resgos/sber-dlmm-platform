# Revenue Research — Post Sprint 5 Monetization Round

**Date**: 2026-06-03 (Sprint 6 day 1)
**Author**: BA lead + PO + CFO + IT-lead.
**Status**: Discovery memo. Top picks merge into `docs/SPRINT-PLAN.md` post-Sprint-6 close.
**Format**: brainstorm → score → recommend.

> **Motivation**: Sprint 5 closed 13 code items and shipped 5 capabilities
> we didn't have at Sprint 4 close: SberSpasibo lane, B2B portal +
> tier-based billing, CBR rates feed, 1С export, NDS-split. Each opens
> downstream monetization that wasn't viable before. This memo catalogues
> what's newly possible and which slots into Sprint 7-8 backlog.
>
> Companion to `docs/MONETIZATION-STRATEGY.md` (Sprint 2 strategic) and
> `docs/RU-MARKET-RESEARCH-2026-05-18.md` (Sprint 5 regulatory). This one
> is the **commercial product layer** that sits on top.

---

## 1. New revenue surfaces unlocked by Sprint 5

For each capability that landed in Sprint 5, identify the monetization
hooks it enables. Most are 1-3 sprint adds, not greenfield work.

### 1.1 SberSpasibo lane (Sprint 5 #5.1-5.5)

| What we have | What we can monetize |
|---|---|
| SSPAS token + pool + webhook bridge + convert API + widget | (a) **Spasibo BU rev-share** — DLMM takes 5-10 bps on convert volume; (b) **Premium Spasibo conversions** — accelerated rate / boosted multiplier as paid feature for Sber Premier customers; (c) **Spasibo arbitrage detection** — alert when Spasibo conversion rate exceeds DLMM market rate on SRUB-equivalent pairs, position for retail-flow capture |

### 1.2 B2B portal + billing (Sprint 5 #5.6-5.7)

| What we have | What we can monetize |
|---|---|
| Issuer registration + KYB workflow + tier pricing engine | (d) **Integration fees** — one-time setup fee per issuer (50k-500k depending on token complexity); (e) **Premium SLA tier** — 99.95% uptime guarantee + dedicated support channel (+50k/мес); (f) **Custom token features** — vesting schedules, transfer restrictions, dividend distribution, all priced as add-ons; (g) **Compliance-as-a-Service** — DLMM handles all regulatory reporting on behalf of the issuer (тарификация от M+) |

### 1.3 CBR rates feed + spread tile (Sprint 5 #5.9)

| What we have | What we can monetize |
|---|---|
| ЦБ РФ daily rates + DLMM spread calculation | (h) **Spread alert subscription** — paid notification when DLMM ↔ CBR spread exceeds X bps (treasurer arbitrage signal, 5k/мес); (i) **Historical spread analytics API** — PRO API tier; (j) **Aggregator brand position** — "DLMM aggregates official CBR + market" as B2B sales talking point |

### 1.4 1С банк-клиент export (Sprint 5 #5.11)

| What we have | What we can monetize |
|---|---|
| 1CClientBankExchange v1.03 export endpoint | (k) **1С enterprise license** — bulk export, scheduled push to corp's 1С server via FTP, monthly retainer 20k; (l) **Reverse direction** — accept payments FROM corp's 1С via standard payment instruction, charge inbound transaction fee 3 bps |

### 1.5 NDS split + tiered fees (Sprint 5 #5.12 + #5.7)

| What we have | What we can monetize |
|---|---|
| Gross/net/VAT-split fee infrastructure | (m) **Fee transparency badge** — visible on corp dashboard, used in compliance reporting; not direct revenue but reduces sales friction (closes deals 20% faster per BA estimate) |

### 1.6 Hedge unwind + margin alerts (Sprint 5 #5.14-5.15)

| What we have | What we can monetize |
|---|---|
| Hedge lifecycle UI + risk alert pipeline | (n) **Managed hedging service** — DLMM auto-rebalances treasurer's hedges on margin alerts (consent-based, 10-15 bps on rebalance volume); (o) **Risk dashboard PRO** — real-time P&L, VaR, scenario analysis (+30k/мес per corp client) |

### 1.7 259-ФЗ verdict — internal accounting units (Sprint 5 #5.D)

| What we have | What we can monetize |
|---|---|
| Compliance verdict allows pilot product expansion without ОИС license | (p) **Tokenized loyalty/voucher issuance** — small businesses can issue UTILITY tokens on DLMM (gift cards, season passes, subscription credits) without ЦФА overhead, 5-10 bps issuance + retainer; (q) **Corp internal-points-as-a-service** — large corps use DLMM as their internal points engine (Sber-Mobility, Sber-Health share points across brands) — bundled into Spasibo BU rev-share |

---

## 2. Net-new monetization ideas (NOT derived from Sprint 5, fresh brainstorm)

### 2.1 Treasurer-side products

| # | Idea | Why it makes sense for RU 2026 |
|---|---|---|
| **M-21** | **Hedge-as-a-Service** для корп клиентов <100M в годовом обороте | Sub-mid-cap treasurers не имеют bandwidth для ручного хеджирования; managed offer monthly + per-rebalance fee. Конкурент — банковский дилер-деск со spread 30-100 bps; мы за flat 15 bps |
| **M-22** | **Custom token issuance for tier-1 corp** (Sber Treasury выпускает свой SBER-USD-LP token, листится через portal) | Sprint 5 portal уже принимает issuer → расширить token-creation API + admin approval pipeline |
| **M-23** | **Cross-bank settlement rail** (Sber → MOEX через DLMM как clearing intermediate) | СПФС не покрывает retail-rouble flows; DLMM может стать «расчётным мостом» для FX-hedge transactions |
| **M-24** | **LP onboarding concierge** для первых 10 Treasury LP'ов | Платная white-glove услуга на $100-200k upfront за каждого: DLMM команда настраивает позиции, мониторит, репортит |

### 2.2 Retail-side products

| # | Idea | Why it makes sense for RU 2026 |
|---|---|---|
| **M-25** | **Real-time analytics PRO** (Pro Components dashboard с historical volume, fee revenue, P&L) | Sprint 5 dashboard уже есть baseline; PRO tier добавляет export, custom date ranges, цвета по pool. 500₽/мес для retail, 5000₽/мес для PRO trader |
| **M-26** | **DLMM Mobile** (iOS + Android) | TAM retail хорошо ловится на mobile; React Native поверх existing API. PO concern: ApppStore + Google Play подача для RU банка — отдельная история, может потребоваться RuStore-first |
| **M-27** | **Сберспасибо subscription tier** — "Spasibo Premium" пользователи получают 1.5× rate на convert | Pair с Sber Premium subscription, rev-share с Premium BU |
| **M-28** | **Social trading / copy-LP** | M-parking — повторное рассмотрение если MAU growth не на цели Q3 |

### 2.3 Институциональные продукты

| # | Idea | Why it makes sense for RU 2026 |
|---|---|---|
| **M-29** | **ЦФА secondary market** (если #6.C Атомайз memo go-ahead) | Vertical-integration: DLMM как ООЦФА для ЦФА, эмитированных у Атомайз/Мастерчейн. 10-30 bps на каждый secondary trade. **R**: needs ООЦФА license (Sprint 7+ Track 3) |
| **M-30** | **MM rebate pre-sell** (Sprint 7 #6.3-6.5 — продажа slot'ов в top-10 MM tiers до launch) | Bronze/Silver/Gold подписки бронируются авансом за квартал; revenue smoothing |
| **M-31** | **OTC desk subscription tier** (за access к лучшим RFQ rate'ам — pay-to-priority) | 100k-500k/мес VIP tier для маркет-мейкеров |
| **M-32** | **Custom regulatory reports** (regulatory landlords like ЦБ Реестр) | Если #6.D Минцифры trek успешен, DLMM становится сертифицирован для гос-контрактов; госструктуры платят за compliance reports |

### 2.4 Data / API monetization

| # | Idea | Why it makes sense for RU 2026 |
|---|---|---|
| **M-33** | **DLMM Public Data API** — bin liquidity snapshots, pool TVL/volume history, ЦБ spread history | Existing — упаковать как Free/Pro/Enterprise tier с rate limits |
| **M-34** | **WebSocket streaming для retail traders** (live bin updates, position P&L feed) | High retention value; pair with M-25 PRO tier |
| **M-35** | **B2B analytics dashboard licensing** — продаём white-label dashboard другим Sber-units для их клиентов | Sber-internal monetization, low TAM but high margin |

### 2.5 Long-tail / contrarian

| # | Idea | Why interesting |
|---|---|---|
| **M-36** | **Liquidity bootstrap incentive marketplace** — issuers платят сейчас за future LP rewards на их пулы | Solves cold-start problem for new tokens; DLMM clips a fee on the bootstrap. Counterpart for sponsored pools (M#12) |
| **M-37** | **DLMM Insurance pool** (opt-in coverage против smart-pool exploits + adverse selection events) | M#18 — parking lot до first claim event. Reconsider if any pool ever loses >10M ₽ |
| **M-38** | **Trade-mining incentives** — пользователи получают SSPAS-эквивалент за каждую swap (paid by sponsored issuers) | Combine M-21 Spasibo BU + M#12 sponsored pools |

---

## 3. Scoring matrix

Same axes as `RU-MARKET-RESEARCH`: Reg necessity (1-5) × Revenue impact (1-5) × Pilot readiness (1-5) × Inverse effort (1-5). Sum ≥ 14 = T1.

| # | Idea | Reg | Rev | Pilot | Effort^(-1) | Sum | Tier |
|---|---|---|---|---|---|---|---|
| **a** | Spasibo BU rev-share contract | 1 | 5 | 3 | 4 | **13** | T2 (PO contract trek) |
| **b** | Premium Spasibo conversion rate | 1 | 3 | 4 | 4 | **12** | T2 — Sprint 8 |
| **c** | Spasibo arbitrage alerts | 1 | 2 | 5 | 4 | **12** | T2 |
| **d** | B2B integration fees | 1 | 4 | 5 | 5 | **15** | **T1 — Sprint 7** |
| **e** | B2B premium SLA tier | 1 | 4 | 4 | 4 | **13** | T2 |
| **f** | Custom token features (vesting, restrictions) | 2 | 5 | 3 | 2 | **12** | T2 — Sprint 8+ |
| **g** | Compliance-as-a-Service | 4 | 5 | 2 | 2 | **13** | T2 (depends Track 3) |
| **h** | CBR spread alert subscription | 1 | 3 | 5 | 5 | **14** | **T1 — Sprint 7** |
| **i** | Historical spread analytics API | 1 | 3 | 5 | 4 | **13** | T2 |
| **j** | Aggregator brand position | 1 | 2 | 5 | 5 | **13** | Marketing trek |
| **k** | 1С enterprise license (FTP push) | 2 | 3 | 4 | 4 | **13** | T2 — Sprint 8 |
| **l** | Reverse 1С direction (inbound from 1С) | 2 | 4 | 3 | 2 | **11** | T2 — Sprint 9+ |
| **m** | NDS transparency badge | 3 | 2 | 5 | 5 | **15** | **T1 — Sprint 7 (1-day sales asset)** |
| **n** | Managed hedging service | 2 | 5 | 3 | 2 | **12** | T2 — Sprint 8+ |
| **o** | Risk dashboard PRO | 1 | 4 | 4 | 3 | **12** | T2 |
| **p** | Tokenized loyalty/voucher issuance | 2 | 4 | 4 | 4 | **14** | **T1 — Sprint 8** |
| **q** | Corp internal-points-as-a-service | 1 | 4 | 2 | 2 | **9** | Parking — re-eval if Spasibo BU contract closes |
| **M-21** | Hedge-as-a-Service for sub-100M corp | 1 | 5 | 3 | 2 | **11** | T2 — Sprint 9+ |
| **M-22** | Custom token issuance tier-1 corp | 2 | 5 | 3 | 3 | **13** | T2 (post M-29 ЦФА decision) |
| **M-23** | Cross-bank settlement rail | 4 | 5 | 1 | 1 | **11** | T2 — Sprint 10+ (heavy reg) |
| **M-24** | LP onboarding concierge | 1 | 4 | 5 | 5 | **15** | **T1 — Sprint 7 (manual service, no code)** |
| **M-25** | Real-time analytics PRO | 1 | 3 | 4 | 4 | **12** | T2 |
| **M-26** | DLMM Mobile (iOS/Android) | 2 | 4 | 1 | 1 | **8** | Parking — re-eval Q4 |
| **M-27** | Spasibo Premium tier | 1 | 3 | 3 | 3 | **10** | T2 — Sprint 9 |
| **M-29** | ЦФА secondary market | 5 | 5 | 1 | 1 | **12** | T2 (depends on #6.C + ООЦФА Sprint 9+) |
| **M-30** | MM rebate pre-sell | 1 | 4 | 5 | 5 | **15** | **T1 — Sprint 6 PO trek (5d)** |
| **M-31** | OTC desk subscription tier | 1 | 4 | 4 | 4 | **13** | T2 — Sprint 7-8 |
| **M-32** | Custom regulatory reports | 4 | 4 | 2 | 2 | **12** | T2 (depends Track 2/3) |
| **M-33** | Public Data API tiers | 1 | 4 | 5 | 4 | **14** | **T1 — Sprint 7 (extends 6.6)** |
| **M-34** | WebSocket streaming | 1 | 3 | 4 | 2 | **10** | T2 — Sprint 9+ |
| **M-35** | B2B dashboard licensing white-label | 1 | 3 | 2 | 2 | **8** | Parking |
| **M-36** | Liquidity bootstrap marketplace | 1 | 4 | 3 | 3 | **11** | T2 — Sprint 8 |
| **M-37** | Insurance pool | 2 | 2 | 1 | 1 | **6** | Parking |
| **M-38** | Trade-mining (Spasibo + sponsored) | 1 | 3 | 3 | 3 | **10** | T2 — pair with M-21 |

---

## 4. Top 6 T1 recommendations — to absorb into Sprint 7-8

### Sprint 7 absorb (alongside OTC+MM bundle moved from Sprint 6)

| # | Idea | Effort | Revenue link |
|---|---|---|---|
| **d** | B2B integration fees | 2d backend (one-time fee column on b2b_issuers, charge on KYB approve) | Direct: 50k-500k per onboarded issuer × ~3 per quarter = 200k-1.5M Q3 |
| **h** | CBR spread alert subscription | 4d (alert config table + scheduler that compares spread vs threshold + email/push) | Direct: 5k/мес × 50-200 treasurers потенциально = 250k-1M/мес at scale |
| **m** | NDS transparency badge | 1d Frontend (add NDS breakdown visible on every B2B invoice download) | Indirect: faster sales close (BA estimate 20% deal cycle reduction) |
| **M-24** | LP onboarding concierge | 0d code (manual service, PO-led) | Direct: 100k-200k upfront × first 10 LPs = 1M-2M Q3-Q4 one-time |
| **M-30** | MM rebate pre-sell | 0d code (PO contract trek + LP-tier waitlist UI Sprint 7+) | Direct: Bronze/Silver/Gold quarterly pre-pay 50k-200k × 8-12 MMs = 1.5M-5M Q3 |
| **M-33** | Public Data API tiers | 3d (extends 6.6 — API key tiers с rate-limits) | Direct: Free/Pro/Enterprise pricing 0/5k/50k/мес × 5-20 API consumers = 100k-500k/мес at scale |

**Sprint 7 net addition: ~10 person-days code + ~3 PO/Sales trek items.**
Fits within Sprint 7 capacity (already absorbs OTC+MM block = 23d from Sprint 6).
Total Sprint 7 reload: ~58d vs 30 capacity = need a second rebalance.

→ **Decision**: only **d + h + M-33 + M-30** code in Sprint 7. M-24 and M is pure-contract (PO trek). Sprint 8 picks up рest.

### Sprint 8 absorb

| # | Idea | Effort |
|---|---|---|
| **p** | Tokenized loyalty/voucher issuance | 5d (extends B2B portal with new token-type wizard) |
| **k** | 1С enterprise license (FTP push) | 4d |
| **b** | Premium Spasibo conversion rate | 3d |
| **e** | B2B premium SLA tier | 3d |
| **g** | Compliance-as-a-Service (initial scope) | 8d (huge — split between Sprint 8/9) |
| **n** | Managed hedging service (design memo only) | 5d SA |
| **o** | Risk dashboard PRO (frontend) | 5d FE |
| **M-22** | Custom token issuance tier-1 corp | 5d (extends B2B portal) |
| **M-31** | OTC desk subscription tier | 3d |
| **M-36** | Liquidity bootstrap marketplace | 5d backend + 3d FE |

Sprint 8 reload: ~44d vs 30 — overload again. **Action**: Sprint 8 takes
**p + k + e + n-memo + M-31 = ~16d** as core, rest moves to Sprint 9.

---

## 5. Revised cumulative revenue model (post-Sprint-5 capabilities)

Previous (MONETIZATION-STRATEGY.md, Sprint 2 baseline):

| Quarter | Stack | Run-rate (₽/year) |
|---|---|---|
| Q3 2026 | protocol fee + exit + custody + Treasury LP + FX hedge pilot + B2B v1 + sponsored pools | 300-500M |
| Q4 2026 | + Spasibo + OTC + MM rebate + DLMM-as-Service (3-5 issuers) | 800M-1.2B |
| Q1 2027 | + Money market + index funds | 1.5-2B |
| Q2 2027 | + Tokenized bonds | 3-4B |

**Revised with this memo's T1 picks layered in:**

| Quarter | Added revenue lines | New run-rate (₽/year) |
|---|---|---|
| Q3 2026 | + B2B integration fees (d) + CBR spread subs (h) + NDS badge (m) + LP concierge (M-24) + MM rebate pre-sell (M-30) + Public Data API tiers (M-33) | **400-650M** (was 300-500M; +30%) |
| Q4 2026 | + Tokenized loyalty (p) + 1С enterprise (k) + Premium Spasibo (b) + B2B premium SLA (e) + Managed hedging design | **1-1.4B** (was 800M-1.2B; +15%) |
| Q1 2027 | + Risk dashboard PRO + Custom token issuance + OTC subscription + Liquidity bootstrap | **1.7-2.3B** (was 1.5-2B; +12%) |
| Q2+ 2027 | + ЦФА secondary market (M-29 if ООЦФА license lands) + Cross-bank settlement | **3.5-5B** (was 3-4B; +20%) |

**Cumulative impact: revenue ceiling raised ~20-30% across all
horizons** purely by stacking the Sprint 5-enabled monetization layers
on top of existing pipeline.

---

## 6. Decisions captured for Sprint 6-7 planning

- [✓] **Sprint 6 stays rebalanced** (per SPRINT-PLAN §6) — no new commercial code, focus on compliance core + carry-overs.
- [✓] **Sprint 7 absorbs M-30 (MM pre-sell PO trek)** as part of original 6.A non-code work — no code add.
- [✓] **Sprint 7 code adds: d (B2B integration fees) + h (CBR spread subs) + M-33 (Public Data API tiers extension on 6.6)** — ~9 person-days addition to already-loaded Sprint 7.
- [✓] **PO + CFO update Q3 run-rate model** post Sprint 5 close to reflect upside (400-650M vs 300-500M).
- [✓] **Sprint 8 backlog seeded** with p + k + b + e + n-memo + M-31 picks for next planning round.
- [✓] **M-29 (ЦФА secondary market) gated on Sprint 6 #6.C memo verdict** — picked up Sprint 9+ if go-ahead.

---

## 7. Open questions / follow-ups

1. **Spasibo BU rev-share contract** — PO needs to formalize % split. Без contract'а monetization (a) висит как «верим что будет».
2. **CBR spread subscription pricing** — нужно market-research (interview 5 corp treasurers сколько готовы платить за такой alert).
3. **Public Data API rate-limit tiers** — какие лимиты для Free/Pro/Enterprise? Pair с Sprint 7 #6.6 (API access tiers).
4. **NDS transparency badge** — UX дизайнер should mock placement before Sprint 7 FE estimate.
5. **MM rebate pre-sell** — нужен Sales pitch deck до Sprint 7 launch.

---

*Recorded by: BA lead. Sign-off: PO, CFO, IT-lead.
Action items in §6. Next revenue review: post Sprint 7 close.*
