package com.sber.dlmm.common.util;

import java.math.BigInteger;

/**
 * Pure swap-fee math on the pool-engine money path. Computes the fee charged
 * on a swap's <em>input</em> amount as {@code base + volatility-variable},
 * capped at {@link #MAX_FEE_BPS}, plus the volatility-accumulator (VA)
 * update/decay helpers that drive the variable term. Stateless,
 * side-effect-free, all-static; safe to call from any thread.
 *
 * <h3>Units and conventions</h3>
 * <ul>
 *   <li>All fee rates are in basis points (bps); 1 bp = 1/10000 of the input.</li>
 *   <li>{@code binStep} is in bps; {@code volatilityAccumulator} (VA) is a raw
 *       bin count (0..maxVolatility), NOT Meteora's 1e4-scaled VA — see
 *       {@link #variableFeeBps(int, int)}.</li>
 *   <li>Amounts are integer base units ({@code long}, the 10⁻⁴ platform scale).</li>
 *   <li><b>Rounding is FLOOR</b> throughout (never over-charge), matching the
 *       rest of the engine; this also keeps a calm market exactly at the base
 *       fee.</li>
 *   <li><b>Overflow safety:</b> the post-scale {@code amountIn * feeBps} and the
 *       squared {@code (VA·binStep)²} are computed in {@link BigInteger}.</li>
 * </ul>
 *
 * <h3>Variable-fee shape (Meteora)</h3>
 * The surcharge follows Meteora's {@code compute_variable_fee} shape,
 * {@code control · (VA · binStep)² / 1e11}, capped at {@link #MAX_FEE_BPS}. At
 * {@code VA == 0} the surcharge is exactly 0, so the total reduces to the pure
 * base fee and VA=0 outputs are byte-identical to the pre-variable-fee engine
 * (every seeded pool has VA=0). Calibration of {@link #VARIABLE_FEE_CONTROL}
 * is provisional pending Stage 2 economics sign-off.
 *
 * @see #variableFeeBps(int, int) the variable-term formula and its Meteora reference
 * @see com.sber.dlmm.common.util.BinMath bin↔price + liquidity / fee-growth math
 */
public final class FeeCalculator {

    /** Non-instantiable static utility holder. */
    private FeeCalculator() {}

    /**
     * Hard cap on the total swap-fee rate: 10% (1000 bps), mirroring Meteora's
     * {@code MAX_FEE_RATE}. Guards against the fee ever exceeding 10% of the input
     * (M-3, math audit) — important once the variable-fee term is made functional,
     * since an uncapped base+variable could exceed 100% and produce a negative net.
     */
    public static final int MAX_FEE_BPS = 1_000;

    /** Basis-point denominator (10000) used to turn a bps rate into a fraction of the amount. */
    private static final BigInteger BPS_DIVISOR = BigInteger.valueOf(10_000L);

    /**
     * Variable-fee control factor for the Meteora-shaped surcharge (Stage 1, math
     * overhaul #14). A plain named constant for now — Stage 2 promotes it to a
     * per-pool {@code variable_fee_control} column (schema change + economics
     * sign-off), exactly as Meteora stores it per pair.
     *
     * <p><b>Calibration (PROVISIONAL — pending Stage 2 economics sign-off).</b>
     * Chosen with {@link #VARIABLE_FEE_DENOMINATOR} = 1e11 so the surcharge is a
     * few bps for moderate volatility on a typical Sber bin step and only
     * approaches the {@link #MAX_FEE_BPS} cap at extreme volatility:
     * <pre>
     *   VA=100,  binStep=20   ->   2 bps      (calm-ish, fine bins)
     *   VA=100,  binStep=100  ->  50 bps      (coarse 1% bins)
     *   VA=1000, binStep=20   -> 200 bps      (a real spike, still &lt; cap)
     *   VA=10,   binStep=100  ->   0 bps      (a calm market stays at base)
     * </pre>
     * 50_000 sits in the same order of magnitude as Meteora's own
     * {@code variable_fee_control} presets (~1e4–1e5).
     */
    public static final long VARIABLE_FEE_CONTROL = 50_000L;

    /**
     * Scale-down divisor for the variable-fee surcharge, taken verbatim from
     * Meteora's {@code compute_variable_fee} (1e11 = its 1e9 fee-rate precision ×
     * the squared-bps 1e2 normaliser). See the formula javadoc on
     * {@link #variableFeeBps(int, int)}.
     */
    private static final BigInteger VARIABLE_FEE_DENOMINATOR = BigInteger.valueOf(100_000_000_000L);

    /**
     * Swap fee charged on the INPUT amount = base + volatility-variable, in bps,
     * capped at {@link #MAX_FEE_BPS}.
     *
     * <p><b>Overflow-safe (Sprint 16):</b> after the 1e-4 platform amount scale,
     * {@code amountIn} is a large raw integer (≈1e16 for a big swap) so
     * {@code amountIn * feeBps} overflows {@code long}. The multiply is done in
     * {@link BigInteger}. (Before the scale, {@code amountIn} was ≤~1e12 and the
     * old {@code amountIn * baseFeeBps / 10_000} fit; post-scale it silently
     * overflowed to a wrong/negative fee on large swaps.)
     *
     * <p><b>Variable term (Stage 1, math overhaul #14):</b> the surcharge now
     * follows Meteora's {@code compute_variable_fee} SHAPE —
     * {@code control · (VA · binStep)² / 1e11} — instead of the old
     * {@code VA² · binStep / 1e10}. See {@link #variableFeeBps(int, int)} for the
     * formula and its Meteora reference. At {@code VA == 0} the surcharge is exactly
     * 0, so {@code calculateSwapFee} reduces to the pure base fee and VA=0 outputs
     * are byte-identical to before this change (every seeded pool has VA=0). The VA
     * reference-frame wiring + per-pool control column are Stage 2 (deferred);
     * calibration is provisional pending that economics sign-off.
     *
     * @param amountIn             swap input amount in base units; ≤ 0 ⇒ fee 0
     * @param baseFeeBps           pool's base fee in bps
     * @param volatilityAccumulator current VA (raw bin count)
     * @param binStep              bin step in bps
     * @return the fee to deduct from {@code amountIn}, in base units, FLOOR-rounded;
     *         0 when {@code amountIn <= 0}
     */
    public static long calculateSwapFee(long amountIn, int baseFeeBps, int volatilityAccumulator, int binStep) {
        if (amountIn <= 0) return 0;
        long feeBps = totalFeeBps(baseFeeBps, volatilityAccumulator, binStep);
        return BigInteger.valueOf(amountIn)
                .multiply(BigInteger.valueOf(feeBps))
                .divide(BPS_DIVISOR)
                .longValueExact();
    }

    /**
     * EXCLUSIVE swap fee: the fee charged <em>on top of</em> a net amount, such that
     * the fee is {@code feeBps} of the resulting gross ({@code net + fee}). Mirrors
     * Meteora's {@code compute_fee(amount)} (the bin-crossing path): when a swap
     * crosses a bin it drains the bin's full output reserve, so {@code net} is the
     * exact input that enters the bin and the fee is added over and above it —
     * {@code fee = net · feeBps / (10000 − feeBps)} — instead of being skimmed out of
     * a fixed gross (that {@code inclusive} form is {@link #calculateSwapFee}, used
     * only for the final partial bin).
     *
     * <p>Identity: with {@code gross = net + fee}, {@code fee == floor(gross·feeBps/10000)}
     * up to ±1 unit, so the LP receives the full bin reserve and the fee rate over the
     * gross matches the displayed rate. FLOOR-rounded like the rest of the engine;
     * {@code feeBps ≤ MAX_FEE_BPS (1000)} so the {@code 10000 − feeBps} denominator is
     * always ≥ 9000 (never zero/negative). {@link BigInteger} guards the multiply.
     *
     * @param netAmount            the net input that will enter the bin (≤ 0 ⇒ fee 0)
     * @param baseFeeBps           pool's base fee in bps
     * @param volatilityAccumulator current VA (raw bin count)
     * @param binStep              bin step in bps
     * @return the fee to add on top of {@code netAmount}, in base units, FLOOR-rounded
     */
    public static long calculateSwapFeeExclusive(long netAmount, int baseFeeBps, int volatilityAccumulator, int binStep) {
        if (netAmount <= 0) return 0;
        long feeBps = totalFeeBps(baseFeeBps, volatilityAccumulator, binStep);
        if (feeBps <= 0) return 0;
        return BigInteger.valueOf(netAmount)
                .multiply(BigInteger.valueOf(feeBps))
                .divide(BigInteger.valueOf(10_000L - feeBps))
                .longValueExact();
    }

    /**
     * Total fee rate in bps = base + variable, capped at {@link #MAX_FEE_BPS} (M-3).
     * Shared by {@code calculateSwapFee} and the pool's displayed "current dynamic
     * fee" so the charged fee and the shown fee can never diverge. Summed as
     * {@code long} before the cap so the {@code base + variable} add cannot overflow.
     *
     * @param baseFeeBps           pool's base fee in bps
     * @param volatilityAccumulator current VA (raw bin count)
     * @param binStep              bin step in bps
     * @return the effective fee rate in bps, in {@code [0, MAX_FEE_BPS]}
     */
    public static int totalFeeBps(int baseFeeBps, int volatilityAccumulator, int binStep) {
        long total = (long) baseFeeBps + variableFeeBps(volatilityAccumulator, binStep);
        return (int) Math.min(total, MAX_FEE_BPS);
    }

    /**
     * Volatility-driven variable fee, in bps, following Meteora's
     * {@code compute_variable_fee} SHAPE (math overhaul #14, Stage 1):
     *
     * <pre>
     *   variableFeeBps = floor( VARIABLE_FEE_CONTROL · (VA · binStep)² / 1e11 )
     * </pre>
     *
     * <p><b>Meteora reference.</b> On-chain Rust {@code commons/src/.../fee.rs}
     * (MeteoraAg/dlmm-sdk) computes
     * {@code variable_fee = variable_fee_control · (volatility_accumulator · bin_step)²},
     * then scales it down by {@code 1e11} (its 1e9 fee-rate precision × the 1e2 that
     * normalises the squared bps), capped at {@code MAX_FEE_RATE}. We keep that exact
     * squared shape and {@code 1e11} divisor. See
     * https://github.com/MeteoraAg/dlmm-sdk and
     * https://docs.meteora.ag/product-overview/dlmm-overview/dynamic-fees .
     *
     * <p><b>Deliberate divergences (documented, in-scope for Stage 1):</b>
     * <ul>
     *   <li><b>VA is unscaled here.</b> Meteora's {@code volatility_accumulator} is
     *       pre-scaled by {@code BASIS_POINT_MAX} (1e4); ours is a raw bin count
     *       (0..maxVolatility). {@link #VARIABLE_FEE_CONTROL} is calibrated for that
     *       raw scale. (Re-basing VA onto Meteora's reference frame is Stage 2.)</li>
     *   <li><b>We FLOOR; Meteora ceils.</b> Flooring is the conservative direction
     *       (never over-charge) and matches the rest of the engine (swap output
     *       floors). It also keeps a calm market (sub-1-bp surcharge) at exactly the
     *       base fee.</li>
     *   <li><b>Control is a constant, not yet per-pool.</b> Stage 2 adds the
     *       {@code variable_fee_control} pool column + economics sign-off.</li>
     * </ul>
     *
     * <p><b>Invariant:</b> at {@code VA == 0} the numerator is 0, so this returns
     * exactly 0 → {@code totalFeeBps == baseFeeBps} → VA=0 fee outputs are
     * byte-identical to pre-Stage-1. The result is capped at {@link #MAX_FEE_BPS} so
     * the surcharge alone can never exceed the global fee ceiling. Computed in
     * {@link BigInteger} so {@code (VA · binStep)²} cannot overflow {@code long} for
     * any input.
     *
     * @param volatilityAccumulator current VA (raw bin count, ≥ 0 in practice)
     * @param binStep               bin step in bps
     * @return variable fee in bps, in {@code [0, MAX_FEE_BPS]}; 0 when VA ≤ 0
     */
    public static long variableFeeBps(int volatilityAccumulator, int binStep) {
        if (volatilityAccumulator <= 0 || binStep <= 0) {
            return 0L;
        }
        BigInteger vaTimesStep = BigInteger.valueOf((long) volatilityAccumulator)
                .multiply(BigInteger.valueOf((long) binStep));
        BigInteger surcharge = BigInteger.valueOf(VARIABLE_FEE_CONTROL)
                .multiply(vaTimesStep.multiply(vaTimesStep))   // control · (VA·binStep)²
                .divide(VARIABLE_FEE_DENOMINATOR);             // FLOOR by 1e11
        // Cap here so the surcharge alone is bounded; totalFeeBps caps the sum again.
        if (surcharge.compareTo(BigInteger.valueOf(MAX_FEE_BPS)) >= 0) {
            return MAX_FEE_BPS;
        }
        return surcharge.longValueExact();
    }

    /**
     * Bumps the volatility accumulator after a swap by the (unsigned) number
     * of bins the swap crossed, clamped to {@code maxVolatility}:
     * {@code min(currentVA + |binsCrossed|, maxVolatility)}.
     *
     * <p>VA only ever rises here; it is brought back down by
     * {@link #decayVolatilityAccumulator(int, int)} between swaps. Direction of
     * the price move is irrelevant, hence {@code Math.abs}.
     *
     * @param currentVA     the bin's current volatility accumulator
     * @param binsCrossed   bins the swap moved through (sign ignored)
     * @param maxVolatility upper clamp for VA (per-pool ceiling)
     * @return the new VA, in {@code [currentVA, maxVolatility]}
     */
    public static int updateVolatilityAccumulator(int currentVA, int binsCrossed, int maxVolatility) {
        int newVA = currentVA + Math.abs(binsCrossed);
        return Math.min(newVA, maxVolatility);
    }

    /**
     * Decays the volatility accumulator toward 0 by {@code decayRate} bps of
     * its current value: {@code currentVA · (10000 - r) / 10000}, where
     * {@code r} is {@code decayRate} clamped to {@code [0, 10000]}. Result is
     * floored at 0. Called periodically by the pool-engine scheduler so VA (and
     * thus the variable fee) relaxes back to base as a market calms.
     *
     * <p><b>Why the clamp (C-3, math audit):</b> the scheduler derives
     * {@code decayRate = 10000·60/decayPeriodSeconds}, which exceeds 10000 for
     * any decay period under 60s; without the clamp {@code (10000 - decayRate)}
     * goes negative and slams VA to 0 every tick. Clamping makes a short decay
     * period decay fast-but-correctly instead of destroying the volatility
     * state. Integer division rounds the decayed value down (FLOOR).
     *
     * @param currentVA the bin's current volatility accumulator
     * @param decayRate decay fraction in bps (e.g. 2000 = decay 20% per tick);
     *                  values outside {@code [0, 10000]} are clamped
     * @return the decayed VA, ≥ 0
     */
    public static int decayVolatilityAccumulator(int currentVA, int decayRate) {
        // C-3 (math audit): clamp the decay rate to [0, 10000]. The scheduler computes
        // decayRate = 10000*60/decayPeriodSeconds, which EXCEEDS 10000 whenever
        // decayPeriodSeconds < 60 → (10000 - decayRate) goes negative → VA·negative →
        // VA wrongly slammed to 0 every tick. Clamping makes a short decay period
        // decay fast-but-correctly instead of destroying the volatility state.
        int r = Math.max(0, Math.min(decayRate, 10_000));
        return Math.max(currentVA * (10_000 - r) / 10_000, 0);
    }
}
