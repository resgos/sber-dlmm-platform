# DLMM Math Audit vs Meteora — 2026-05-31

Full audit of the swap/fee/liquidity math against Meteora's Liquidity Book
(on-chain Rust: `MeteoraAg/dlmm-sdk` — `commons/src/extensions/lb_pair.rs`,
`bin.rs`, `math/price_math.rs`, `constants.rs`, `quote.rs`; docs.meteora.ag).

## Verdict

The swap **geometry is correct and matches Meteora**: traversal direction
(X→Y walks down, Y→X up), fee-on-input (`netIn = amountIn − fee`), and
`Rounding::Down` (FLOOR) on output so the pool never loses dust to the trader.
`BinMath.binLiquidity` / `LbDlmmMath` already encode Meteora's `L = x·price + y`.

The gaps cluster in the **fee/volatility model** and **liquidity accounting**.

## Fixed this session (safe, tested)

| Ref | Fix | File |
|---|---|---|
| **overflow** | `amountIn * baseFeeBps` overflowed `long` after the 1e-4 platform amount scale (amountIn ≈1e16 on big swaps → wrong/negative fee). Fee multiply now in `BigInteger`. | `FeeCalculator.calculateSwapFee` |
| **M-3** | Added `MAX_FEE_BPS = 1000` (10%) cap on the total fee rate, mirroring Meteora `MAX_FEE_RATE`. | `FeeCalculator.totalFeeBps` |
| **C-3** | VA decay rate clamped to `[0,10000]`. The scheduler's `10000*60/decayPeriodSeconds` exceeds 10000 when `decayPeriodSeconds < 60`, making the multiplier negative and zeroing VA every tick. | `FeeCalculator.decayVolatilityAccumulator` |
| dedup | `PoolService.calculateDynamicFee` now calls the shared `FeeCalculator.totalFeeBps` (was a duplicated, drift-prone, uncapped formula) so the displayed fee == charged fee. | `PoolService` |
| custody | Custody-fee watermark advanced by the days actually charged (`since + days`) instead of `now`, so sub-day + over-90d-cap remainders aren't dropped (systematic under-charge). | `CustodyFeeAccrualService` |

Pinned by 4 new `FeeCalculatorExtendedTest.MathAuditFixTests` (+ all 198 common
/ 85 pool-engine tests green).

## Flagged — dedicated, economics-reviewed effort (NOT rushed here)

These are correctness-relevant but high-blast-radius (swap hot path + DB reseed +
test rewrites + fee-economics calibration). The team already deferred the
variable-fee piece (see the `@Disabled volatileMarket` test). Treat as one
"DLMM math overhaul" sprint.

- **C-1 / C-2 — Dynamic fee + volatility accumulator are non-functional.**
  Variable fee uses `binStep¹` (Meteora squares `(VA·binStep)`), drops the
  `variable_fee_control` factor, and truncates to ~0; VA is an unscaled bin
  count (Meteora scales by `BASIS_POINT_MAX` and uses a decaying reference
  frame). Port Meteora's `compute_variable_fee` + `update_references` /
  `update_volatility_accumulator` (1e9 rate precision). Needs a
  `variable_fee_control` pool column + reseed + fee-economics review + test
  rewrites. *Today: swaps charge only `baseFeeBps`, which is admin-bounded, so
  the platform is bounded-but-not-volatility-responsive.*
- **C-4 — Canonical `L = x·price + y` units (= deferred P0-1).** Add-liquidity
  writes `liquidity = amountX + amountY` (dimensionally broken; composition
  factor drifts past 1.0 on non-1-priced pools and is tactically clamped).
  `LbDlmmMath` already documents the migration. High swap-pipeline regression
  risk — affects composition, fee-growth-per-share, APY on every non-1-priced
  pool.
- **M-1 — `activeBinId = 2^23` anchor** is non-canonical (Meteora: id=0⇒price=1,
  range ±443636) and makes `binPrice`/`binPriceAtBin` dead in the hot path
  (swap reads the stored `bin.price` column). Standing footgun; needs a full
  re-seed to migrate.
- **M-2 — Protocol fee split** uses percent `/100` (cap 5%) + FLOOR; Meteora
  uses bps `/10000` (cap 25%). Dust goes to LPs (safe direction). Granularity
  + reference divergence only.
- **M-4 / m-6** — `priceToBinId` uses `double Math.log` (precision at edges,
  masked by M-1); bin-exhaustion under fee-on-input strands a few units per bin.

Priority when picked up: **C-1+C-2 together** (they mask each other) → **C-4** →
the rest.

Sources: https://github.com/MeteoraAg/dlmm-sdk ·
https://docs.meteora.ag/product-overview/dlmm-overview/dynamic-fees
