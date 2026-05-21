package com.sber.dlmm.common.util;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

public final class BinMath {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");

    private BinMath() {}

    /**
     * Low-level: price = basePrice * (1 + binStep/10000)^binId.
     *
     * <p>{@code binId} is an <em>offset from the zero bin</em>, NOT an
     * absolute LB-DLMM binId. For an absolute binId use the safer
     * {@link #binPriceAtBin(BigDecimal, int, int, int)} helper, which
     * subtracts {@code activeBinId} for you.
     *
     * <p>Direct callers (the tests, mostly) usually pass small offsets
     * like 0 / ±1 / ±10 where the distinction doesn't matter. Business
     * code that touches a pool's absolute binId must NOT call this
     * directly — see Sprint 9-DS-r3 incident note in the class Javadoc.
     */
    public static BigDecimal binPrice(BigDecimal basePrice, int binStep, int binId) {
        BigDecimal factor = BigDecimal.ONE.add(new BigDecimal(binStep).divide(TEN_THOUSAND, MC));
        // Previously a naive O(N) loop. With seed pools using activeBinId =
        // 8_388_608 (2^23) that was 8.3M BigDecimal multiplies per pool —
        // ~700ms each. /api/v1/pools on 22 pools took ~17s and stalled the
        // admin dashboard's WebClient call. BigDecimal.pow(int) is O(log n)
        // via square-and-multiply, dropping the per-pool cost to <1ms.
        if (binId == 0) {
            return basePrice.setScale(18, RoundingMode.HALF_UP);
        }
        BigDecimal factorToBinId = factor.pow(Math.abs(binId), MC);
        BigDecimal price = binId > 0
                ? basePrice.multiply(factorToBinId, MC)
                : basePrice.divide(factorToBinId, MC);
        return price.setScale(18, RoundingMode.HALF_UP);
    }

    /**
     * Sprint 9-DS-r4 (P0-2) — price at an absolute LB-DLMM
     * {@code binId}, anchored at {@code activeBinId} (where the price
     * equals the pool's {@code basePrice}).
     *
     * <p>Backstory: every direct call site used to do<pre>
     *     BinMath.binPrice(basePrice, binStep, binId - activeBinId)
     * </pre>and was repeatedly written as the bare<pre>
     *     BinMath.binPrice(basePrice, binStep, binId)            // BUG
     * </pre>which silently overflows. Seed pools use
     * {@code activeBinId = 2^23 = 8 388 608} (Trader Joe LB convention,
     * picked so a u24 column can hold negative offsets without a sign
     * column), so the bug raises a small multiplier to ~8M and the
     * result is meaningless — but no exception, just a 100% price
     * impact downstream. Encapsulating the offset subtraction here
     * makes the bug structurally impossible at the call site.
     *
     * @param basePrice  pool's stored base price (Y per 1 X at active)
     * @param binStep    bin step in basis points
     * @param binId      absolute LB-DLMM bin id
     * @param activeBinId pool's active bin id (the price anchor)
     */
    public static BigDecimal binPriceAtBin(BigDecimal basePrice, int binStep,
                                            int binId, int activeBinId) {
        return binPrice(basePrice, binStep, binId - activeBinId);
    }

    public static int priceToBinId(BigDecimal basePrice, int binStep, BigDecimal price) {
        if (basePrice.compareTo(BigDecimal.ZERO) <= 0 || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Prices must be positive");
        }
        double logRatio = Math.log(price.doubleValue() / basePrice.doubleValue());
        double logBase = Math.log(1.0 + binStep / 10_000.0);
        return (int) Math.round(logRatio / logBase);
    }

    public static BigDecimal compositionFactor(long reserveY, long liquidity) {
        if (liquidity == 0) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(reserveY).divide(new BigDecimal(liquidity), MC);
    }
}
