# UI Research & Clarity Review — 2026-05-29

Post feature-batch (PC-02 / OB-01 / SM-01 / SK-01 / T-01 / DS-02) UI research:
verify everything live, **work through all past remarks**, and check that the UI
is **понятно** (clear) to a non-expert user. Live stack on :3000 (admin) / :3001
(user), both themes where relevant.

---

## 1. Past remarks — worked through

| ID | Remark | Status | Evidence |
|----|--------|--------|----------|
| **F-09** | fee→₽ rollups show 0 (user KPIs + admin «Собрано комиссий») | ✅ fixed both | user `/fees/me/summary` totalUnclaimed/Claimed real; admin `totalFeesCollectedRub` **4.58 млрд** (pool-engine redeployed) |
| **F-10** | admin Users «Последний вход» = "—" | ✅ fixed | `/users` returns real `lastLoginAt` |
| **F-11** | admin Pools stat cards summed only the page | ✅ fixed | aggregates all 22 pools (2.31 трлн) |
| **F-13 (chart badge)** | price-chart «+9190.78%» outlier | ✅ fixed | OHLCV backfill base-price fallback → badge **+0.14%**, axis 95.05–95.35 |
| **F-13 (health count)** | admin dash «0 из 8 в норме» (green dots) | ✅ fixed | nginx `/actuator` proxy + honest `fetchHealth` → **«8 из 8 в норме»** |
| **F-13 (SBTC TVL)** | pool-health table SBTC «1500 квадриллион ₽» | ✅ fixed | `poolTvlRub` → `totalTvlX+totalTvlY` → SBTC **2.13 трлн**, ≤ platform total, coherent with KPI |
| **F-01** | pools APY/Volume = 0 | ✅ holding | 05-seed refresh; APY 0.09–3.6%, volume non-zero |
| **F-02** | chart "freeze" | ✅ n/a | CDP-screenshot artifact only; lightweight-charts canvas screenshots fine; pages responsive |
| **F-07** | price-impact "странный" | ✅ fixed earlier | net-based, 0% within-bin (verified: 1000→0%, 50B→0.275%) |
| **price-impact** | (user-flagged twice) | ✅ `computePriceImpact` helper, live-verified | |
| **F-12** | **remove-liquidity over-returns quote token** | 🟠 **OPEN** — documented + recommendation below | isolated add→remove: deposited Y=336666, returned 500566 (+49%) |
| **F-08** | unrealistic seed TVL (квадриллион) | 🟡 partially — rescaled earlier (06/08); SBTC still an outlier (2.13 трлн), now displayed coherently everywhere | |

## 2. Clarity review — «всё понятно?»

Methodology: read each surface as a non-expert treasury user. Verdict: **largely
clear** — the batch specifically improved comprehension. Notable positives:

- **Simple⇄Pro (SM-01)** is the single biggest clarity win — Simple mode hides
  bins/strategies/ranges entirely: just «Купить / Продать» + «Добавить ликвидность
  по базовым настройкам». A novice never sees DLMM jargon.
- **Сберкот (SK-01)** gives a plain-language hint on every screen («Это стакан —
  клик по строке подставит цену», «В Simple это шаг пропускается…»), dismissible +
  remembered. Directly serves «понятно».
- **Strategy names localised** (T-01): Равномерная / Концентрированная /
  Двусторонняя instead of raw SPOT/CURVE/BID_ASK.
- **Chart** labels its source («внутренние данные») and reality («22 свечи · по
  реальным сделкам пула»). **Order book** says «из бинов пула» (explains the
  DLMM-native source). Admin trend series honestly labelled «ряд смоделирован».

Minor clarity nits (low priority, not blocking):
- «ряд смоделирован» / «оценка» on admin trends — honest but a tooltip explaining
  *why* (no historical series yet) would help. 
- «Стакан» assumes trading literacy; the Сберкот hint mitigates.
- The 14-digit bin IDs are hidden in Simple but visible in Pro tooltips — fine for
  the Pro audience.

No remaining **misleading numbers** after this session (the three F-13 sub-bugs
were the real "непонятно" issues — all fixed: a chart that claimed +9190%, a
health card that claimed 0/8 up, a pool bigger than the whole platform).

## 3. F-12 — assessment & recommendation (the one open correctness issue)

**Confirmed root cause:** 13 of 22 active bins violate the bin invariant
`reserveX·price + reserveY = liquidity` (compositionFactor > 1; SBTC 998×). The
seed's 210 history swaps were SQL inserts that set `pool_bins` reserves without
maintaining `liquidity`; `SwapService` itself conserves L correctly. On remove,
`amountY = reserveY · shares / liquidity` over-pays because `reserveY/liquidity > 1`.

**Impact:** an LP withdrawing principal takes more quote-token than deposited, at
other LPs' expense. Pool-internal conservation still holds (reserve drop = user
gain + exit fee), so no money is minted — it's an unfair split. Worst on SBTC.

**Recommendation (needs its own test cycle — NOT a hot patch):**
1. Seed-reconciliation script that recomputes each bin so
   `liquidity = round(reserveX·price + reserveY)` **and** scales the existing
   `position_bins.shares` by the same factor (so no position's payout shifts).
2. Add a `TvlReconciliationService`-style invariant check + a test that asserts
   `reserveY ≤ liquidity` for every bin after seeding.
3. Until then: avoid live add+remove on SBTC in demos (documented in
   `docs/CONSISTENCY-CHECK-2026-05-29.md`). Swap / add / claim are exact.

## 4. Remaining backlog (tracked, not regressions)
- **LO-02** limit-order UI (mock-ready), **MB-01** mobile, **DS-01** design-polish
  sweep + hex-baseline rebaseline (SberkotMascot brand-hex + 3 pre-existing files),
  **PC-01 / LO-01** backend (external sync / limit engine).

## Verdict
The platform reads **clear and coherent** after this session. Every numeric
"непонятно" bug surfaced in research is fixed (fees, last-login, pool stats, chart
%, health count, pool TVL). The new surfaces (chart/стакан/Simple/Сберкот) and the
redesigned admin dashboard match their specs with real, sane data. One correctness
defect (F-12, remove-liquidity) remains documented with a concrete fix path.
