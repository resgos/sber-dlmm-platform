package com.sber.dlmm.common.util;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Canonical Liquidity-Book DLMM math, in the convention Trader Joe LB
 * and Meteora both use. Foundation for the Sprint 9-DS-r4 (P0-1)
 * migration off of the mixed-unit
 * <pre>liquidity = amountX + amountY</pre>
 * accounting that {@link com.sber.dlmm.common.util.BinMath} and
 * {@code PoolBin.liquidity} currently rely on.
 *
 * <h3>Why this exists</h3>
 *
 * The codebase today stores {@code PoolBin.liquidity = amountX + amountY},
 * which is dimensionally broken — X and Y have different units and price.
 * It happens to "work" because seed pools use {@code basePrice ≈ 1} so
 * the units coincide, but as soon as we hit a non-1-priced pool (SBTC
 * at 5M ₽, SGAZP at 200 ₽) the composition factor
 * <pre>c = reserveY / liquidity</pre>
 * drifts outside [0,1] and we have to clamp tactically (see
 * {@code LiquidityService}, comment marked Sprint 9-DS-r3).
 *
 * <h3>Canonical definition</h3>
 *
 * Per Trader Joe LB whitepaper §3.1 and Meteora DLMM docs, within a
 * single bin at price {@code p} (Y per 1 X):
 *
 * <pre>
 *   L = x · p + y                  (1) — liquidity measured in Y units
 *   c = y / L                      (2) — composition factor in [0,1]
 *   x = L · (1 - c) / p            (3) — back out X from L + c
 *   y = L · c                      (4) — back out Y from L + c
 * </pre>
 *
 * Key invariants this guarantees that mixed-unit accounting violates:
 * <ul>
 *   <li>{@code c ∈ [0,1]} always — no tactical clamp needed.</li>
 *   <li>Adding two bins' liquidity at the same price gives a meaningful
 *       sum: {@code L_total = (x1+x2)·p + (y1+y2)}.</li>
 *   <li>Composition is invariant under reserve scaling: doubling both
 *       reserves leaves {@code c} unchanged.</li>
 * </ul>
 *
 * <h3>Migration plan (deferred to Sprint 10)</h3>
 *
 * <ol>
 *   <li>Compute {@code PoolBin.liquidity} via {@link #liquidity} on every
 *       write (deposit, swap-side update, remove-liquidity). Add a feature
 *       flag {@code dlmm.pool.canonical-liquidity=true}.</li>
 *   <li>Backfill existing rows from {@code reserveX/reserveY/bin price}.</li>
 *   <li>Switch readers ({@code compositionFactor},
 *       {@code calculateDistributionWeights}, fee allocation) to use the
 *       canonical formulae here.</li>
 *   <li>Drop the {@code [0,1]} clamp in {@code LiquidityService}.</li>
 *   <li>Remove the legacy mixed-unit code path.</li>
 * </ol>
 *
 * <h3>Why not the Uniswap-style √(xy) invariant?</h3>
 *
 * Uniswap V2's {@code k = x · y} (and its derivative {@code L = √(x·y)})
 * applies to a single global pool with continuous price discovery. LB-DLMM
 * discretises price into bins of constant width and reserves only one
 * side per bin away from active — there's no continuous {@code xy = k}
 * invariant to preserve, so the Uniswap form would not give the same
 * composition semantics.
 */
public final class LbDlmmMath {

    private static final MathContext MC = MathContext.DECIMAL128;

    private LbDlmmMath() {}

    /**
     * Canonical liquidity within a single bin: {@code L = reserveX · binPrice + reserveY}.
     *
     * <p>Both reserves are integer base units (long), but the product
     * with {@code binPrice} is fractional, so the result is returned as
     * {@code BigDecimal}. Callers that need to persist this as a long
     * should round-down (FLOOR) the result, mirroring how the existing
     * {@code PoolBin.liquidity} long is treated.
     *
     * @throws IllegalArgumentException if {@code binPrice <= 0}
     */
    public static BigDecimal liquidity(long reserveX, long reserveY, BigDecimal binPrice) {
        if (binPrice == null || binPrice.signum() <= 0) {
            throw new IllegalArgumentException("binPrice must be positive, got " + binPrice);
        }
        return new BigDecimal(reserveX).multiply(binPrice, MC)
                .add(new BigDecimal(reserveY), MC);
    }

    /**
     * Composition factor {@code c = reserveY / L}, always in {@code [0,1]}
     * by construction. Returns 0 when liquidity is zero (empty bin).
     */
    public static BigDecimal compositionFactor(long reserveY, BigDecimal liquidity) {
        if (liquidity == null || liquidity.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(reserveY).divide(liquidity, MC);
    }

    /**
     * Back out X reserves from {@code L, c, binPrice}:
     * {@code x = L · (1-c) / p}. Returned as long with FLOOR rounding
     * to match the on-disk integer reserve representation.
     */
    public static long reserveX(BigDecimal liquidity, BigDecimal compositionFactor, BigDecimal binPrice) {
        if (binPrice == null || binPrice.signum() <= 0) {
            throw new IllegalArgumentException("binPrice must be positive, got " + binPrice);
        }
        BigDecimal oneMinusC = BigDecimal.ONE.subtract(compositionFactor, MC);
        return liquidity.multiply(oneMinusC, MC)
                .divide(binPrice, MC)
                .setScale(0, RoundingMode.FLOOR)
                .longValueExact();
    }

    /**
     * Back out Y reserves from {@code L, c}: {@code y = L · c}.
     */
    public static long reserveY(BigDecimal liquidity, BigDecimal compositionFactor) {
        return liquidity.multiply(compositionFactor, MC)
                .setScale(0, RoundingMode.FLOOR)
                .longValueExact();
    }
}
