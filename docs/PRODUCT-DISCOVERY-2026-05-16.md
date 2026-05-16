# Mini-demo discovery — 2026-05-16

**Participants**: PO, System Analyst (SA), Business Analyst (BA)
**Duration**: 25 min walkthrough + 30 min discussion
**Goal**: PO/SA show the system; BA offers fresh-eyes product perspective.

---

## Walkthrough snapshot (live at meeting)

```
totalPools:        22 (all ACTIVE)
verifiedUsers:     3 of 4
volume24hRub:      2.14 B
activePositions:   11
transactionsToday: 7 (today's; not the seeded backlog)
totalFeesCollectedRub: 0  ← protocol fee not enabled yet (KEY FINDING)
```

**Top-5 pools by 24h volume:**

| X | Y | bin_step | fee_bps | TVL_sum | volume_24h |
|---|---|---|---|---|---|
| SBTC | SETH | 50 | 20 | 700B | 616M |
| ROSN | SRUB | 10 | 25 | 104T | 339M |
| SBTC | SRUB | 100 | 25 | 300B | 257M |
| SGOLD | SRUB | 50 | 15 | 850B | 171M |
| LKOH | SRUB | 10 | 20 | 163T | 160M |

**Token catalogue spread:**
- EQUITY_TOKEN (10): SBER, GAZP, LKOH, GMKN, ROSN, MGNT, YNDX, TATN, NLMK, VTBR
- COMMODITY_BACKED (7): SBTC, SGOLD, SSILV, SPLAT, SPALD, SOIL, SNGAS
- FIAT_BACKED (4): SRUB, SUSDT, SCNY, SEUR
- INDEX_TOKEN (2): SMOEX, SRTSI
- UTILITY (1): SETH

---

## BA's observation that changed the meeting

> "У вас технологически очень сильный продукт. Outbox, healthchecks,
> Prometheus, тесты — это уровень банка топ-3. **Но я не понимаю что
> вы продаёте.** 22 пула, 4 пользователя, ивановы перекидывают SBTC.
> Это технологическое демо, а не бизнес."

PO acknowledged: revenue model was implicit, never stated. Discussion
pivoted from "what's built" to "what would someone pay for".

---

## Three revenue directions BA proposed

### 1. DLMM-as-a-Service for token issuers (B2B)

Self-service onboarding for any RF company issuing a utility / fiat
/ security token. We provide the matching engine + KYC umbrella +
regulatory shield. Pricing: 500k RUB listing fee + 1.25 bps of pool
volume (5% of base fee).

- **Already in place**: 95% of infra. `POST /api/v1/tokens` and
  `POST /api/v1/pools` already work — we just need the B2B portal
  and KYB workflow on top.
- **Missing**: B2B portal, due-diligence workflow, billing.
- **Market**: ~50 utility-token issuers in RF today, ~250 projected
  by 2027. Average revenue/issuer ~5.5M RUB/year.
- **Total addressable**: ~1.5 B RUB/year at full penetration.
- **Time-to-money**: 2–3 months from kickoff.
- **Moat**: nobody in RF does this — Uniswap/Curve are USD-denom,
  not regulated by CBR.

### 2. Corporate FX hedges via stablecoin pools (B2C-corp)

Sber's 80k corporate clients who import from China currently hedge
SRUB/CNY through bank dealer desks at 30+ bps with min-lot 50M.
Our SRUB/SCNY pool (already in seed) gives the same hedge at 25 bps,
any size, T+0 settlement.

- **Already in place**: pools SRUB/SCNY, SRUB/SUSDT, SRUB/SEUR; swap
  API; KYC.
- **Missing**: counterparty exposure limits, margin-call logic,
  SBBOL integration, settlement report for corp accounting.
- **Market**: 2024 RF corp FX hedge volume ~30T RUB through NDFs.
  Capturing 0.1% = 30B RUB volume.
- **Revenue at 25 bps**: ~75M RUB/year.
- **Time-to-money**: 1–2 months for pilot with 3–5 anchor clients.
- **Moat**: Sber owns the client relationship + the corp banking
  channel.

### 3. Market-maker rebate program (institutional)

Enable `protocol_fee_pct = 20%` on pools (today it's 0). Of that,
80% goes to MM rebate pool distributed pro-rata to top-10 LPs by
share. 20% is our margin. Bronze/Silver/Gold tiers.

- **Already in place**: pool fee accounting; `unclaimed_fee_x/y` per
  position; `total_fees_collected_x/y` per pool.
- **Missing**: protocol-fee-pct activation, MM tier table, rebate
  scheduler (already on Sprint 3 backlog as "fee distribution"),
  MM legal agreement template.
- **Market** (back-of-envelope): 5 MMs each providing 1B RUB
  liquidity → 5B TVL → ~500M daily volume → 1.25M daily fee →
  80% (1M) to MMs, 20% (250k) to protocol = **~90M RUB/year margin**.
- **Time-to-money**: 3–4 months including MM onboarding.

### Comparison matrix (BA at whiteboard)

| Criterion | DLMM-as-Service | FX hedges | MM rebate |
|---|---|---|---|
| Time-to-money | 2–3 mo | 1–2 mo | 3–4 mo |
| Infra work | medium | small | medium |
| Regulatory risk | medium | low | low |
| Annual market size | 1.5 B | 75 M | 90 M |
| Competition in RF | none | bank dealer desks | none |
| Uses DLMM uniqueness | high | medium | high |
| Demo-ready via | new portal | existing swap | scheduler stub |

**BA recommendation**: Start with FX hedges (fastest revenue),
build DLMM-as-Service in parallel as the strategic pillar.

---

## What we're committing to

### For the demo (in 2 weeks)

| Owner | Action |
|---|---|
| PO | Slide "FX hedge vs dealer desk" with numbers from corp-sales |
| SA | `docs/USE-CASE-FX-HEDGE.md` + `scripts/demo-fx-hedge.ps1` |
| BA | Play CFO of corp client during demo rehearsal |
| All | Add FX-hedge scenario to `docs/DEMO-SCRIPT.md` §2 |

### For Sprint 3 (BA-driven additions to backlog)

1. **Enable `protocol_fee_pct = 5%` by default** + admin UI for tuning.
   Otherwise we're gifting 100% of fees to LPs forever.
2. **Real price oracle SRUB/CNY** (closes risk #11 from register).
   Required for FX hedge use case.
3. **B2B portal for issuers — design sketch** (no code yet, 1 epic
   of design work to validate scope before Sprint 4 build).

### For Q3 2026 (PO planning)

- **FX hedge pilot** with 3–5 anchor corp clients (revenue target by Q3 close)
- **DLMM-as-Service portal v1** (2 sprints of build after design sprint)

### Backlog parking (not now, but don't lose)

| Idea | Why interesting |
|---|---|
| Public status.dlmm.sber-online.ru | Trust signal for retail; complements internal /actuator |
| Index funds (token = basket of SBER+GAZP+...) | Sber-branded MOEX10 ETF on chain |
| Social trading (copy LP strategies) | Retail acquisition; viral growth lever |
| Commit-reveal swap (privacy) | Institutional swap > 100M without mempool front-run |
| DAO governance for pool params | Decentralization narrative for PR / press |

---

## Open questions parked for follow-up

- **CFO question**: at protocol_fee_pct=5%, what's the breakeven user
  count? PO owes a model.
- **Compliance question**: does enabling protocol fee change the
  regulatory frame from "internal clearing" to "broker"? Legal owes
  a memo before activation.
- **Tech question**: same-pool row lock from Sprint 2 k6 → if 5 MMs
  all swap the same SRUB/SBTC pool concurrently, we serialise. Need
  per-bin lock or optimistic-locking analysis before MM program
  goes live.

---

*Recorded by: SA. Owner of follow-up: PO. Next checkpoint:
dress-rehearsal in 7 days.*
