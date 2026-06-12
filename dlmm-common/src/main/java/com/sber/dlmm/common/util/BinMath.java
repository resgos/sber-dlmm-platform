package com.sber.dlmm.common.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Pure DLMM bin math used on the pool-engine money paths: bin↔price
 * conversion, composition factor, the canonical bin-liquidity invariant and
 * the LP fee-growth fixed-point accumulator. Stateless, side-effect-free,
 * all-static; safe to call from any thread.
 *
 * <h3>Units and conventions</h3>
 * <ul>
 *   <li>{@code price} is Y per 1 X (token_y per token_x).</li>
 *   <li>{@code binStep} is in basis points (1 bp = 1/10000); the geometric
 *       ratio between adjacent bins is {@code 1 + binStep/10000}.</li>
 *   <li>Reserves and liquidity are integer base units ({@code long}); 1 unit
 *       is 10⁻⁴ of a token (the uniform platform scale, #14).</li>
 *   <li>Internal fractional math uses {@link MathContext#DECIMAL128}; values
 *       persisted as {@code long} are rounded as documented per method.</li>
 * </ul>
 *
 * <h3>Bin-id convention (Sprint 9-DS-r3 incident)</h3>
 * Seed pools use {@code activeBinId = 2^23 = 8 388 608} (Trader Joe LB
 * convention, chosen so a u24 column can hold negative offsets without a sign
 * column). {@link #binPrice} takes an <em>offset</em> from the zero bin, NOT
 * an absolute binId — passing an absolute binId raises the per-step ratio to
 * ~8M and yields a meaningless price with no exception. Business code that
 * holds an absolute binId must go through
 * {@link #binPriceAtBin(BigDecimal, int, int, int)}, which subtracts
 * {@code activeBinId} for you and makes the mistake structurally impossible.
 *
 * @see com.sber.dlmm.common.util.LbDlmmMath canonical (dimensionally-correct)
 *      liquidity model that this class's mixed-unit accounting is migrating to
 * @see com.sber.dlmm.common.util.FeeCalculator swap-fee (base + variable) math
 */
public final class BinMath {

    /** 34-digit decimal precision used for all intermediate {@link BigDecimal} math. */
    private static final MathContext MC = MathContext.DECIMAL128;
    /** Basis-point base (10000): the per-step price ratio is {@code 1 + binStep/10000}. */
    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");

    /** Non-instantiable static utility holder. */
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

    /**
     * Inverse of {@link #binPrice}: the bin <em>offset</em> whose price is
     * closest to {@code price}, i.e.
     * {@code round( ln(price/basePrice) / ln(1 + binStep/10000) )}.
     *
     * <p>Returns an offset from the zero bin (same frame as
     * {@link #binPrice}), NOT an absolute binId — add {@code activeBinId}
     * to obtain an absolute id. Rounds to the nearest bin (HALF_UP on the
     * exponent). Computed in {@code double}, so it is intended for bin
     * selection, not for exact-price arithmetic.
     *
     * @param basePrice anchor price at the zero bin (Y per 1 X); must be &gt; 0
     * @param binStep   bin step in basis points
     * @param price     target price (Y per 1 X); must be &gt; 0
     * @return the nearest bin offset for {@code price}
     * @throws IllegalArgumentException if {@code basePrice <= 0} or {@code price <= 0}
     */
    public static int priceToBinId(BigDecimal basePrice, int binStep, BigDecimal price) {
        if (basePrice.compareTo(BigDecimal.ZERO) <= 0 || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Prices must be positive");
        }
        double logRatio = Math.log(price.doubleValue() / basePrice.doubleValue());
        double logBase = Math.log(1.0 + binStep / 10_000.0);
        return (int) Math.round(logRatio / logBase);
    }

    /**
     * Legacy mixed-unit composition factor {@code reserveY / liquidity},
     * where {@code liquidity} is the {@code PoolBin.liquidity} long.
     *
     * <p><b>Caveat:</b> because the legacy {@code liquidity} is stored as the
     * dimensionally-broken {@code reserveX + reserveY} (see
     * {@link LbDlmmMath}), this ratio is only well-behaved in {@code [0,1]}
     * for {@code basePrice ≈ 1} pools and can drift outside it otherwise. The
     * canonical, always-{@code [0,1]} version is
     * {@link LbDlmmMath#compositionFactor(long, BigDecimal)}.
     *
     * @param reserveY  token_y reserve of the bin (base units)
     * @param liquidity the bin's stored liquidity ({@code long}); 0 ⇒ empty bin
     * @return {@code reserveY / liquidity}, or {@link BigDecimal#ZERO} when
     *         {@code liquidity == 0}
     */
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
     * Non-positive inputs → 0. Rounds down (integer division / FLOOR).
     *
     * @param lpFee     the LP portion of the swap fee for this bin (base units)
     * @param liquidity the bin's liquidity at the time of the swap
     * @return the scaled per-unit-of-liquidity fee-growth increment to add to
     *         the bin's accumulator; 0 if {@code lpFee <= 0} or {@code liquidity <= 0}
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
     * edge to no-fee instead of a negative). Rounds down (integer division /
     * FLOOR) and so undoes the {@link #FEE_GROWTH_SCALE} applied by
     * {@link #feeGrowthIncrement}.
     *
     * @param feeGrowthDelta the bin's accumulated fee-growth minus the
     *                       position's last-settled snapshot (scaled units)
     * @param shares         the position's share of the bin
     * @return the fee owed to the position (base units); 0 if
     *         {@code feeGrowthDelta <= 0} or {@code shares <= 0}
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
     * Overflow-safe {@code floor(value · numerator / denominator)} for the
     * engine's pro-rata splits (a position's share of a bin's reserve:
     * {@code reserve · shares / liquidity}). A raw {@code long} product
     * overflows on the post-×10⁴ demo magnitudes — e.g. reserveY 1.7e13 ×
     * shares 1.6e10 ≈ 2.8e23 ≫ Long.MAX ≈ 9.2e18 — and wraps NEGATIVE, which
     * surfaced as negative position values (audit B6) and corrupt withdraw
     * amounts. Non-positive inputs short-circuit to 0, same convention as
     * {@link #feeFromGrowth}.
     *
     * @param value       the quantity being split (e.g. a bin reserve)
     * @param numerator   the held share (e.g. the position's shares in the bin)
     * @param denominator the whole (e.g. the bin's total liquidity)
     * @return {@code floor(value·numerator/denominator)}, or 0 when any input
     *         is non-positive
     */
    public static long mulDiv(long value, long numerator, long denominator) {
        if (value <= 0 || numerator <= 0 || denominator <= 0) {
            return 0L;
        }
        return BigInteger.valueOf(value)
                .multiply(BigInteger.valueOf(numerator))
                .divide(BigInteger.valueOf(denominator))
                .longValueExact();
    }

    /**
     * DLMM bin invariant: a bin's liquidity (in token_y units) is the value it
     * holds — {@code reserveX·price} (token_x valued in token_y) plus
     * {@code reserveY}. The seed reconciliation
     * (docker/10-seed-reconcile-bin-invariant.sql) and any code that
     * (re)derives liquidity from reserves must agree on THIS formula, or an
     * isolated add→remove over-/under-returns (the F-12 family).
     *
     * <p>This is the {@code long}-valued counterpart of the canonical
     * {@link LbDlmmMath#liquidity(long, long, BigDecimal)} formula (1). Unlike
     * the strict canonical helper it is defensive for money-path use: negative
     * reserves are treated as 0, a missing/zero {@code price} drops the X term,
     * an empty bin returns 0, and the final value is clamped to be ≥ 0.
     *
     * <p><b>Rounding:</b> HALF_UP to the nearest whole unit (NOT floor — this
     * is the canonical liquidity figure both the writer and the reconciliation
     * agree on, so it is rounded to nearest rather than truncated).
     *
     * @param reserveX token_x reserve of the bin (base units); ≤ 0 ⇒ X term 0
     * @param reserveY token_y reserve of the bin (base units); &lt; 0 treated as 0
     * @param price    bin price, Y per 1 X; null/≤ 0 ⇒ X term contributes 0
     * @return the bin's liquidity in token_y units, ≥ 0; 0 when both reserves
     *         are non-positive
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
