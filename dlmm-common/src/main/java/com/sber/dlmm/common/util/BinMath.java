package com.sber.dlmm.common.util;

import java.math.BigDecimal;
import java.math.BigInteger;
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

    // ─── LP fee-growth fixed-point (Sprint 10 / P0 fix) ────────────────────
    /**
     * Fixed-point scale for per-unit-of-liquidity fee growth.
     *
     * <p>The LP fee-growth accumulator is {@code lpFee / liquidity}, which in
     * plain {@code long} arithmetic floors to 0 whenever a swap's LP fee is
     * smaller than the bin's liquidity — i.e. essentially always — so LPs
     * accrued nothing. We scale the increment up by this factor before the
     * integer division (and divide it back out when paying a position) so the
     * sub-unit ratio survives. 1e9 keeps ~9 significant digits without
     * overflowing the accumulated {@code long} over any realistic swap count.
     */
    public static final long FEE_GROWTH_SCALE = 1_000_000_000L;

    /**
     * Scaled fee-growth increment for one swap on a bin:
     * {@code (lpFee * FEE_GROWTH_SCALE) / liquidity}. BigInteger intermediate
     * so the {@code lpFee * SCALE} product cannot overflow {@code long}.
     * Non-positive inputs → 0.
     */
    public static long feeGrowthIncrement(long lpFee, long liquidity) {
        if (lpFee <= 0 || liquidity <= 0) {
            return 0L;
        }
        return BigInteger.valueOf(lpFee)
                .multiply(BigInteger.valueOf(FEE_GROWTH_SCALE))
                .divide(BigInteger.valueOf(liquidity))
                .longValue();
    }

    /**
     * Fee owed to a position for a fee-growth delta:
     * {@code (feeGrowthDelta * shares) / FEE_GROWTH_SCALE}. BigInteger
     * intermediate so the {@code delta * shares} product cannot overflow.
     * Non-positive delta or shares → 0 (clamps the "snapshot newer than bin"
     * edge to no-fee instead of a negative).
     */
    public static long feeFromGrowth(long feeGrowthDelta, long shares) {
        if (feeGrowthDelta <= 0 || shares <= 0) {
            return 0L;
        }
        return BigInteger.valueOf(feeGrowthDelta)
                .multiply(BigInteger.valueOf(shares))
                .divide(BigInteger.valueOf(FEE_GROWTH_SCALE))
                .longValue();
    }

    /**
     * DLMM bin invariant: a bin's liquidity (in token_y units) is the value it
     * holds — {@code reserveX·price} (token_x valued in token_y) plus
     * {@code reserveY}. The seed reconciliation
     * (docker/10-seed-reconcile-bin-invariant.sql) and any code that
     * (re)derives liquidity from reserves must agree on THIS formula, or an
     * isolated add→remove over-/under-returns (the F-12 family).
     */
    public static long binLiquidity(long reserveX, long reserveY, BigDecimal price) {
        if (reserveX <= 0 && reserveY <= 0) {
            return 0L;
        }
        BigDecimal x = (reserveX > 0 && price != null)
                ? new BigDecimal(reserveX).multiply(price)
                : BigDecimal.ZERO;
        long l = x.add(new BigDecimal(Math.max(0L, reserveY)))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
        return Math.max(0L, l);
    }
}
