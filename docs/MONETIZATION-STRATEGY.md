# Sber DLMM — Monetization Strategy

**Audience**: PO, CFO, Treasury BU, Compliance.
**Author**: BA, post mini-demo discovery 2026-05-16.
**Status**: Recommendation for Sprint 3 planning + Q3 roadmap.

> Companion to `docs/PRODUCT-DISCOVERY-2026-05-16.md`. That doc surfaced
> the gap (`totalFeesCollectedRub = 0`). This doc proposes how to close
> it across three time horizons.

---

## 1. Frame reset

We've been positioning as a "DLMM trading platform". That's too narrow.

What we actually have:
- A matching engine for 22 pools across 5 asset classes
- A **bank-grade compliance stack** (JWT + KYC + outbox + audit)
- Sber brand + CBR regulatory standing
- Access to 100M+ retail + 80k corporate KYC'd customers (if we sell internally)
- SberBusiness, SberID, SberSpasibo as distribution channels

That isn't a trading platform — that's **a financial rail on the
regulated Sber perimeter**. Trading sits on it; so can payments,
lending, custody, and loyalty. The matching engine is the
table-stakes, not the moat.

---

## 2. Sber moats — what to filter ideas through

| Moat | What it unlocks |
|---|---|
| Banking license + custody | Hold rubles on balance sheet, licensed asset custody |
| 100M+ retail KYC'd | Any retail product gets free activation cost |
| 80k corp clients | B2B-channel distribution without cold sales |
| CBR regulatory standing | "Internal clearing" free option — no broker-dealer license |
| SberSpasibo (60M+ MAU) | Loyalty as conversion lever |
| Sber Treasury (trillions RUB) | Internal MM with effectively unlimited capital |
| Cyrillic-first + RU support | Competitors USD-denom and English-only |

**Filter rule**: each idea must leverage at least **two** moats.
Otherwise any CEX can clone it and we have no advantage.

---

## 3. Catalog (60+ revenue mechanisms, 10 categories)

Cast a wide net first. Filter later. Categories:

A. Per-transaction (8 ideas)
B. SaaS / subscriptions (7 ideas)
C. AUM-based (5 ideas)
D. Spread / banker classic (5 ideas)
E. Data + intelligence (7 ideas)
F. Credit + leverage (6 ideas)
G. Distribution + partnerships (5 ideas)
H. Compliance / regulatory products (5 ideas)
I. Adjacent financial products (6 ideas)
J. Network effects / ecosystem (6 ideas)

Full catalog in BA's working notes — see Section 3 of the discovery
memo. Below: only what survives the Sber moat filter.

---

## 4. Filter — what we reject

| Bucket | Examples | Why we reject |
|---|---|---|
| Regulatory landmine | margin trading without risk infra; prediction markets; commit-reveal/privacy swap; MEV games | License revocation single-event risk |
| Cannibalizes trust | hidden fees; PII resale; aggressive cross-sell | Erodes the KYC'd-user-pool moat itself |
| Strategic misfit | white-label to Tinkoff/VTB; NFT auctions; carbon credits | Wrong brand or wrong RF market today |
| Small TAM | premium retail analytics; DLMM Academy | <50M RUB/year ceiling, not worth focus |

~30 survivors of 60.

---

## 5. Contrarian insights (the non-obvious bets)

### Insight 1 — The moat is the user pool, not the engine
Any CEX clones DLMM math in 6 months. Nobody clones 100M Sber-verified
RUB-balance users. Prioritize products that monetize **"verified user
with balance > 0"**, not products that monetize "user wants to swap".

→ Custody fee, lending, vault products, idle-balance interest spread
are higher leverage than pure trading volume growth.

### Insight 2 — Anchor pricing to TraDFi, not DeFi
We don't compete with Uniswap (5 bps). We displace Raiffeisen's dealer
desk (30-100 bps). Don't undercut our own pricing power by anchoring
on crypto rates.

→ B2B products: 50 bps. Retail: 25 bps for marketing.

### Insight 3 — Free option on the regulatory frame
While we stay "internal clearing" (Sber-issued tokens, Sber-verified
users), no broker-dealer license needed. Open the platform to 3rd
party token issuers and we become a broker — +1 year of licensing.

→ Tier the platform: **Sber Inner** (monetize now, free option)
vs **Sber Open** (build in parallel with license work).

### Insight 4 — Biggest revenue is internal
Sber Treasury has trillions in idle RUB. Even 0.1% deployed as LP in
our pools → daily fee revenue ×10. **Internal sale, not external.**
Treasury gets 25 bps on swaps vs ~5 bps on overnight RUONIA. Win-win.

→ Highest-ROI activity this month: not new code, but a **CFO meeting**
to pitch Treasury on the platform as a yield venue.

### Insight 5 — SberSpasibo is a 60M-user activation
60M MAU on loyalty points. Tokenize as SSPAS, add SSPAS/SRUB pool,
every retail loyalty action becomes a swap. **Engineering work, not
new product invention.** Massive scale for low effort.

### Insight 6 — Negative-correlation portfolio
High volume → derivatives, leverage spike.
Low volume → custody, lending, treasury services spike.

Build 3-4 streams across the cycle so revenue stabilises through
crypto winter/summer. Classic banking diversification playbook.

### Insight 7 — SRUB as payment rail, not trading asset
If SRUB is positioned as "digital ruble for Sber-to-Sber settlement",
B2B payments become swap-volume. Corp → corp at 5 bps vs 50 bps
interbank. Volume scales ×100. Regulatorily cleaner: settlement
between verified parties, not speculation.

---

## 6. Scoring (top 20 candidates)

`Score = TAM × TTM-inverse × Strategic-fit / (Reg-risk × Cannibalization)`

| # | Idea | Annual TAM (RUB) | TTM mo | Infra% | Fit | Reg | Score |
|---|---|---:|---:|---:|---:|---:|---:|
| 1 | **Enable protocol_fee_pct = 5%** | 30M → 500M+ | <1 | 100 | 5 | 1 | **45** |
| 2 | **Sell Sber Treasury as LP venue** | 200M+ year-1 | 1 | 100 | 5 | 1 | **40** |
| 3 | **Corp FX hedges (SRUB/SCNY,USD,EUR)** | 75M+ | 2 | 90 | 5 | 2 | **35** |
| 4 | **B2B settlement rail (SRUB transfers)** | 300-500M | 3 | 80 | 5 | 1 | **35** |
| 5 | **SberSpasibo → SSPAS integration** | massive (hard TAM) | 4 | 70 | 5 | 2 | **30** |
| 6 | **Custody fee 5 bps p.a.** | 50-100M | 1 | 100 | 4 | 2 | **28** |
| 7 | **OTC desk for block trades** | 200M+ | 4 | 60 | 4 | 2 | **25** |
| 8 | **Exit fee 10 bps on LP close** | 10-30M | <1 | 100 | 3 | 1 | **20** |
| 9 | **Tokenized money market (yield SRUB)** | 300M+ | 6 | 50 | 4 | 3 | **20** |
| 10 | **MM rebate program** | 90M | 4 | 80 | 4 | 2 | **18** |
| 11 | **DLMM-as-a-Service for issuers** | 1.5B long-term | 6 | 60 | 5 | 3 | **18** |
| 12 | **Sponsored pool placement** | 50M | 2 | 90 | 3 | 1 | **15** |
| 13 | Collateralized lending (Aave-style) | 500M+ | 8 | 30 | 4 | 4 | 12 |
| 14 | Tokenized bonds | 1B+ long-term | 12 | 20 | 5 | 4 | 10 |
| 15 | Index funds (basket tokens) | 200M | 6 | 40 | 4 | 3 | 10 |
| 16 | Vault strategies (auto-rebalance) | 100M | 8 | 30 | 3 | 2 | 8 |
| 17 | Embedded swap widget SDK | 50M | 6 | 50 | 3 | 2 | 8 |
| 18 | Insurance pool (opt-in coverage) | 30M | 8 | 40 | 3 | 3 | 6 |
| 19 | Derivatives (options/futures) | 1B+ | 18+ | 10 | 4 | 5 | 5 |
| 20 | API access paid tiers | 20M | 3 | 80 | 2 | 1 | 5 |

---

## 7. Recommended sequence — three horizons

### ⚡ NOW (Sprint 3-4, before demo + Q3 start) — fix the economics

**Budget**: 1.5 epics, ~3-4 weeks of dev + sales effort.

1. **Enable `protocol_fee_pct = 5%`** (Sprint 3)
   - Stop gifting 100% fees to LPs. 1 day of code + admin UI.
   - Conditional on legal memo (Insight 3).

2. **Sell Sber Treasury as LP venue** (sales, not tech)
   - PO + CFO + Treasury head meeting. 0 code.
   - Even 100M placed → 10× daily fee revenue.

3. **Custody fee 5 bps p.a.** (Sprint 4)
   - Scheduled accrual job + migration. ~2 days.
   - Soft user comms in UI.

4. **Exit fee 10 bps on LP close** (Sprint 4)
   - 0.5 day of code, churn-reducing + revenue tail.

### 📈 NEXT (Q3-Q4 2026) — strategic builds

5. **Corp FX hedges** (BA's idea from prior memo)
6. **B2B settlement rail (SRUB)** — *new flagship pick*
7. **SberSpasibo → SSPAS integration** — *massive distribution lever*
8. **DLMM-as-a-Service for issuers** (BA's idea, long-term pillar)
9. **OTC desk for block trades**

### 🎯 LATER (2027 H1+) — big bets

10. **Tokenized money market fund** (yield-bearing SRUB)
11. **Tokenized bonds** (sovereign + corp)
12. **Index funds** (SBER10, MOEX10 on chain)
13. **Collateralized lending** (after risk infra matures)

---

## 8. Stacking math (cumulative run-rate)

| Quarter | Newly active streams | Annual run-rate |
|---|---|---:|
| Q3 2026 | Protocol fee + exit + custody + Treasury sale | **150-200M ₽** |
| Q4 2026 | + FX hedges pilot, B2B settlement v1, sponsored pools | **300-500M ₽** |
| Q1 2027 | + SberSpasibo, OTC desk, MM rebate | **800M-1.2B ₽** |
| Q2 2027 | + DLMM-as-Service (3-5 issuers), money market | **1.5-2B ₽** |
| Q3 2027 | + Tokenized bonds pilot, index funds | **3-4B ₽** |

**Key**: no single idea is a billion. **Stacking 7-8 compatible
streams** delivers 3-4B over 4 quarters. That's mid-size bank revenue
on top of infra that's mostly built.

---

## 9. Anti-patterns to avoid (with rationale)

| Anti-pattern | Why dangerous |
|---|---|
| Hidden / surprise fees | Kills trust → retail churn → erodes the KYC user-pool moat itself |
| Selling order-flow data | Regulatory landmine + reputation hit. CBR closes us in months. |
| Aggressive retail cross-sell | Doesn't fit Sber's institutional brand |
| Margin trading without proper risk infra | Single-event blowup → license revocation. Hold until Q4-2026 |
| White-label to Tinkoff/VTB | Won't buy from Sber. Regional banks: TAM doesn't cover sales effort. |
| RF tax-loss harvesting | Tax-grey-area, regulatory risk for users |
| Premium retail analytics | TAM <50M, not worth marketing cost |

---

## 10. Open questions (resolve before Sprint 3)

1. **Legal**: does `protocol_fee_pct > 0` shift our reg-frame from
   internal clearing to broker-dealer?
   *Owner*: Compliance lead. *Deadline*: 1 week.

2. **Treasury BU**: will Sber Treasury place even 100M as pilot LP?
   *Owner*: PO + CFO meeting. *Deadline*: 2 weeks (pre-demo).

3. **SberSpasibo BU**: integration scope for SSPAS tokenization?
   *Owner*: PO + Loyalty product head. *Deadline*: Q3 planning.

4. **Tech**: same-pool row lock (Sprint 2 k6 finding) — if Treasury
   becomes dominant LP, all retail swaps serialise on its row.
   *Owner*: SA + IT-lead. *Deadline*: before Treasury onboarding.

5. **Compliance**: B2B settlement as "transfer" vs "swap" — different
   regulatory categories. Which is easier to scale?
   *Owner*: Compliance. *Deadline*: before B2B settlement build starts.

---

## 11. One-line summary for PO

> **Stop thinking we're building an exchange. We're building a
> financial rail on a regulated perimeter. Trading, payments, lending,
> custody, loyalty all sit on the rail. Today the rail carries trading
> and gives away the fees. Turn on protocol fee, sell Sber Treasury as
> the LP venue, add SberSpasibo as distribution. By year-end revenue
> goes from 30M to 1-2B without a single architectural revolution.**

---

*Recorded: 2026-05-16. Next checkpoint: Sprint 3 planning. Owner of
follow-up: PO.*
