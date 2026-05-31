# Math overhaul #14 — staged implementation plan (2026-05-31)

> Produced by a planning subagent. Companion to `docs/MATH-AUDIT-METEORA-2026-05-31.md`.
> This is the **plan**, not code. Core money math — flagged "don't rush" (#34).

## Three facts that shape everything
1. **The swap hot path reads the materialized `bin.price` column, not `BinMath`.** `binPriceAtBin` + the 2^23 anchor are effectively dead in swap math — they matter only for add-liquidity / `PoolRepricer` / display.
2. **Existing fee tests hard-code the *current* (buggy) formula as golden values.** Any Meteora port changes the fee number and breaks them *by design* — they must be re-derived from Meteora source, not read back from new code.
3. **VA never moves in seeds** (all `volatility_accumulator = 0`, most swaps single-bin). So `totalFeeBps == baseFeeBps` always today → **at VA=0 the fee is byte-identical**, which is what makes Stages 1+3 safe.

## Staged sequence (smallest-safe-first)
| Stage | Item | Risk | Verdict |
|---|---|---|---|
| **0** | Characterization/golden harness (tests only, no prod change) | none | **Do first always** |
| **1** | Faithful Meteora variable-fee shape in `FeeCalculator` `((VA·binStep)²·control)`, behind golden parity vectors; VA inputs unchanged | low–med | **Safe incremental win** — byte-identical at VA=0 |
| **2** | Wire the VA reference frame (`index_reference`+decay) into pool+swap so VA actually moves on multi-bin sweeps | med | **Dark-launch behind control=0; needs schema cols + economics sign-off** (changes live fee revenue) |
| **3** | Re-align `activeBinId`↔`basePrice` in `PoolRepricer`+`createPool`; make `priceToBinId` exact | med | **Safe-ish, no reseed** — conservative slice of M-1; fixes the ~1% offset |
| **4** | Canonical `L = x·price + y` in add-liquidity (C-4) | **high** | **Defer** — redefines the LP-share unit; needs flag + live share-rescale + golden gate |
| **5** | Migrate anchor to id=0⇒price=1 | **high** | **Defer indefinitely** — full reseed, lowest payoff (hot path doesn't read it) |

## Safety net (formula-independent invariants to assert)
Pool conservation per swap; F-12 (`liquidity == reserveX·price + reserveY`); fee bounds (`0 ≤ fee ≤ amountIn`, `≤ MAX_FEE_BPS`); add→remove round-trip (no value created/destroyed) on price=1 **and** price≠1. Golden-diff: VA=0 outputs must stay byte-identical after Stages 1–3.

## Recommendation
**Do Stages 0+1+3** as a dedicated, test-first effort (safe, no reseed, fixes the dormant-fee + anchor-offset). **Stage 2** only behind a control-defaulted-to-0 flag + an economics calibration sign-off (it changes fee revenue). **Stages 4–5 stay parked** — they need a reconciliation sprint, exactly the "don't rush" the audit flagged.

Critical files: `dlmm-common/.../FeeCalculator.java`, `dlmm-common/.../BinMath.java`, `dlmm-common/.../LbDlmmMath.java`, `dlmm-pool-engine/.../SwapService.java`, `LiquidityService.java`, `PoolRepricer.java`; tests `FeeCalculatorTest`, `FeeCalculatorExtendedTest`, `SwapServiceTest`, `PoolRepricerTest`.
