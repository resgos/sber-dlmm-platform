# Use case: Corporate FX hedge via Sber DLMM

**Status**: Sprint 3 day 3 — runtime-verified, demo-ready.
**Owner**: SA (technical depth) + PO (commercial story).
**Audience**: corp client CFO, treasury manager.

**How to demo live**: `pwsh -File scripts/demo-fx-hedge.ps1`
(walks through the scenario below against the local stack).

**Verified live (Sprint 3 day 3):**
- 10M SRUB units → 76.5M SCNY units in **<200ms** end-to-end
- Fee: 1M SRUB units (10 bps, matches `pool.base_fee_bps=10` on SRUB/SCNY)
- Outbox: 2 BalanceMutated events + 1 SwapExecuted event, all
  published to Kafka within ~1s
- Same hedge through a dealer desk would have been a phone call,
  30–100 bps spread, T+2 settlement

---

## The pain (CFO POV)

A mid-cap Russian importer pays a Chinese supplier 50M CNY in 30 days.
At today's rate that's ~640M RUB. If RUB depreciates 5% in those 30
days, the importer overpays 32M RUB — a year's worth of marketing
budget gone to FX volatility.

Today's options for hedging:

| Channel | Spread | Min lot | T+ | Onboarding |
|---|---|---|---|---|
| Bank dealer desk (Raiffeisen, ING) | 30–100 bps | 50M ₽ | T+2 | weeks |
| Moscow Exchange FX forward | 5–15 bps | 100M ₽ | T+2 | KYC for direct, otherwise broker |
| Crypto stablecoin OTC | 50–200 bps | none | T+0 | offshore, AML risk |

The mid-cap with 10M ₽ to hedge has **no good option**.

---

## What Sber DLMM offers

A pool of SRUB/SCNY (Sber's CNY-pegged stablecoin) with the same DLMM
mechanics as crypto pairs. The corporate user:

1. Logs in via SberID → already KYC'd, no separate onboarding.
2. Opens `/swap`, picks SRUB → SCNY direction, enters notional.
3. Gets a real-time quote: `executionPrice`, `priceImpactPct`,
   `feeAmount`.
4. Executes — settled in ~200ms; SCNY shows in the user's balance.
5. To unwind 30 days later: same flow in reverse.

Spread: 25 bps base fee. With Sprint 3's protocol_fee_pct = 5%
activation, Sber's cut is 1.25 bps; the rest accrues to LPs (which
will include Sber Treasury after the M#2 onboarding).

---

## The demo (3-minute live walk-through)

1. **Setup** — admin pre-warms: dashboard, SRUB/SCNY pool detail.
   Story: "представим Иван Иванов, CFO мидкап-импортёра".
2. **Open user UI as ivanov@example.com** (Demo1234).
3. **Navigate to Swap**. Select pool SRUB/SCNY.
4. **Enter 10,000,000 SRUB** as amount-in. Quote appears inline:
   `≈ 1,440,000 SCNY`, fee `2,500 SRUB` (25 bps), impact `0.04%`.
5. **Execute**. Toast notification. Balance updates.
6. **Switch to admin UI**. Show the transaction in /admin/transactions.
   Highlight: settled in <200ms, recorded in transactions ledger +
   outbox event for downstream analytics.
7. **30-day fast-forward** — manually advance pool price (use admin
   API to simulate rate move). Run reverse swap. Show P&L.

---

## Talking points (for PO during the demo)

- **Speed**: 200ms settlement vs 2-day forward.
- **Size**: any notional vs 50M+ min lot.
- **Cost**: 25 bps vs 30-100 bps dealer.
- **Compliance**: same KYC umbrella as SberBusiness — no separate
  AML onboarding. Counterparty risk = Sber (vs offshore OTC desk).
- **Reporting**: settlement CSV (Sprint 4 #4.4) feeds directly to
  corp 1С / SAP. No reconciliation.
- **Available pairs day 1**: SRUB/SCNY, SRUB/SEUR, SRUB/SUSDT. Adding
  SBRL/SCHF / SBRL/SJPY = Sprint 5.

---

## The math the CFO will check

For 50M ₽ hedge:
- **Raiffeisen dealer desk** (30 bps): 150,000 ₽ cost.
- **Sber DLMM** (25 bps): 125,000 ₽ cost.
- **Savings on one hedge**: 25,000 ₽ (small).
- **Savings on 50 hedges/year**: 1.25M ₽ (one junior analyst's salary).

But the **real value** isn't the 5 bps. It's:
- T+0 settlement → no dealer desk relationship overhead
- Any size → mid-caps stop self-insuring FX risk
- Audit trail by default → no separate reporting build

---

## Open implementation items (Sprint 4 deliverables)

| Item | Why needed | Owner |
|---|---|---|
| Counterparty exposure limits | A 1B ₽ corp client can't accidentally take 100% of pool TVL | Backend (4.2) |
| Margin-call notification | If pool TVL changes, client's open hedge value moves; need email/SMS | Backend (4.3) |
| Settlement CSV report | Corp accountant needs to reconcile against 1С | Backend (4.4) |
| SBBOL integration | Single sign-on from corp banking portal | Sber integrations (4.5/4.C) |
| Real SRUB/CNY oracle | Today's demo runs on stub prices | SA (Sprint 3 #3.5) |

---

## Pilot client criteria (Sprint 4 #4.B)

PO + Corp Sales pick 3–5 clients matching:
- Mid-cap with active SRUB ↔ CNY exposure (importer or exporter)
- Existing SberBusiness account
- ≥ 100M ₽ annual FX flow
- Willing to give written feedback over 4 weeks

Target signed pilots: 1 by Sprint 4 close, 3 by end of Q3.

---

*Created: 2026-05-16, Sprint 3 prep. Will be expanded with measured
numbers from the first pilot client in Sprint 4.*
