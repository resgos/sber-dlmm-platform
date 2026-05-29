# Core-Operations Consistency Check — 2026-05-29

**Ask:** verify the most important flows — create/remove liquidity, swaps, fees —
and check **consistency** (no value created/destroyed; deltas match across
balance ↔ pool reserve ↔ position records).
**Method:** `scripts/consistency-check.sh` — DB (psql) is ground truth for state;
curl drives operations through the gateway. Every case asserts exact integer
deltas. Pool: **SUSDT/SRUB** (`c0…110`), user **ivanov**.

## Result: 3 of 4 flows exactly consistent; 1 real defect (F-12)

| Flow | Verdict | Evidence |
|------|---------|----------|
| **SWAP** | ✅ exact | 100000 SRUB in → pool SRUB reserve +100000; 1049 SUSDT out = pool SUSDT reserve −1049; fee 100 (=10 bps) booked to `total_fees_collected_y`; priceImpact 0% (within active bin) |
| **ADD LIQUIDITY** | ✅ exact | consumed 3534 SUSDT + 673332 SRUB = reserve increase = recorded `initial_deposit_x/y`, to the unit; new position created |
| **CLAIM FEES** | ✅ exact | position had 165 000 000 unclaimed (90M SUSDT + 75M SRUB) → balances +165 000 000 exactly; `fee_accruals` flipped to claimed; `/fees/me/summary.totalUnclaimed` dropped by the claimed amount |
| **REMOVE LIQUIDITY** | ⚠️ **F-12** | pool-internal conservation holds (reserve drop = user gain + exit fee, both legs) **but** removing returns ~49% more quote-token (SRUB) than was deposited |

## F-12 🔴 — remove-liquidity over-returns the quote token (bin invariant violated)

**Repro (isolated, zero swaps between add & remove):**
deposited X=1767 Y=336666 → removed X=1768 **Y=500566**. Net Y **+163900 (+49%)**;
X exact. No swaps occurred between add and remove, so this isn't impermanent-loss
rebalancing — it's an accounting defect.

**Root cause:** the per-bin invariant `reserveX·price + reserveY = liquidity`
(⇒ `compositionFactor = reserveY/liquidity ∈ [0,1]`) is **violated on 13 of 22
active bins**. Examples (bin 8388608):

| pair | liquidity | reserve_y | compositionFactor |
|------|-----------|-----------|-------------------|
| SBTC | 1 800 360 000 | 1 797 295 500 000 | **998.3** |
| SUSDT | 875 760 198 | 1 731 066 631 | **1.977** |
| SCNY | 319 318 181 | 540 505 596 | **1.693** |

A bin's `reserveY` exceeds its `liquidity`, which is impossible in a consistent
DLMM bin. Mechanics of the over-return:
- **add** (`LiquidityService` L220-230): mints `posBin.liquidityShares = binLiquidity`
  and adds `poolBin.liquidity += binLiquidity`, while reserves get
  `computeBinAmounts` (for the active bin, `amountY = binLiquidity·c` with `c`
  **clamped to [0,1]**, hiding the real ratio).
- **remove** (L539-540): `amountY = reserveY · shares / liquidity`. With
  `reserveY/liquidity ≈ 1.98`, shares redeem ~1.98× the deposited Y.

**Origin = seed data, not the swap engine.** `SwapService` (L458-466) mutates only
`reserveX/reserveY` and never `liquidity` — so live swaps correctly conserve L.
The desync was baked in by the seed: the 210 synthetic "swaps" in
`03-seed-trading-history.sql` (and the `06`/`08` rescales) UPDATE `pool_bins`
reserves directly without maintaining `liquidity = reserveX·price + reserveY`.
SBTC's 998× is why that pool is such a TVL outlier.

**Impact:** an LP withdrawing principal from an affected bin takes more quote-token
than they put in, at other LPs' expense (the pool itself doesn't mint money —
conservation holds — but the split is unfair, and on SBTC a single remove could
drain ~1000× the position's principal).

**Recommended fix (NOT a pre-demo rush — needs its own test cycle):**
regenerate the seed so each bin satisfies `liquidity = round(reserveX·price + reserveY)`
(and existing position shares are scaled consistently), **or** add a bin-reconciliation
step. Until then: **do not perform live add+remove liquidity on SBTC during the demo.**
Swaps, add-liquidity, and fee-claim are safe and exact.

## Notes
- The first swap attempt returned HTTP 500 — a transient `TimeoutException`
  (3s Resilience4j read timeout) on the first cross-service call after the
  user-service/fee-service restart (cold downstream). Warm retry → HTTP 200.
  Not a logic bug; a StartupWarmer covering the swap KYC/deduct path would
  remove the cold-start blip.
