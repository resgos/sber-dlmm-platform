package com.sber.dlmm.common.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 10 (P0 fix) — pins the LP fee-growth fixed-point math.
 *
 * <p>The old accumulator {@code lpFee / liquidity} floored to 0 whenever a
 * swap's LP fee was smaller than bin liquidity (essentially always), so LPs
 * accrued nothing. {@link BinMath#feeGrowthIncrement} scales the increment up
 * by {@link BinMath#FEE_GROWTH_SCALE} before the integer divide and
 * {@link BinMath#feeFromGrowth} divides it back out, using BigInteger
 * intermediates so neither product overflows {@code long}.
 */
class BinMathFeeGrowthTest {

    @Test
    void incrementIsNonZeroForSmallFeeOnLargeLiquidity() {
        // The exact case the bug floored to 0: fee (2 400) << liquidity (1e8).
        long inc = BinMath.feeGrowthIncrement(2_400L, 100_000_000L);
        assertTrue(inc > 0, "scaled increment must not floor to 0, was " + inc);
    }

    @Test
    void ownerOfAllLiquidityReclaimsTheLpFee() {
        long lpFee = 2_400L;
        long liquidity = 100_000_000L;
        long inc = BinMath.feeGrowthIncrement(lpFee, liquidity);
        // A position holding ALL the bin's liquidity gets ~the whole lpFee back.
        long fee = BinMath.feeFromGrowth(inc, liquidity);
        assertTrue(Math.abs(fee - lpFee) <= 1, "expected ~" + lpFee + " got " + fee);
    }

    @Test
    void halfOwnerGetsHalf() {
        long lpFee = 10_000L;
        long liquidity = 1_000_000_000L;
        long inc = BinMath.feeGrowthIncrement(lpFee, liquidity);
        long feeHalf = BinMath.feeFromGrowth(inc, liquidity / 2);
        assertTrue(Math.abs(feeHalf - lpFee / 2) <= 1, "expected ~" + (lpFee / 2) + " got " + feeHalf);
    }

    @Test
    void noOverflowOnLargeFeeTinyLiquidity() {
        // lpFee 5e9 with liquidity 1e3 → intermediate 5e9 * 1e9 = 5e18; a plain
        // long product would be near the limit, BigInteger keeps it exact.
        long inc = BinMath.feeGrowthIncrement(5_000_000_000L, 1_000L);
        assertTrue(inc > 0);
        long fee = BinMath.feeFromGrowth(inc, 1_000L);
        assertTrue(Math.abs(fee - 5_000_000_000L) <= 1_000L, "expected ~5e9 got " + fee);
    }

    @Test
    void nonPositiveInputsReturnZero() {
        assertEquals(0L, BinMath.feeGrowthIncrement(0L, 100L));
        assertEquals(0L, BinMath.feeGrowthIncrement(100L, 0L));
        assertEquals(0L, BinMath.feeFromGrowth(-5L, 100L)); // negative delta clamps to 0
        assertEquals(0L, BinMath.feeFromGrowth(100L, 0L));
    }

    @Test
    void binLiquidityMatchesTheInvariant() {
        // reserveX·price + reserveY, in token_y units (the F-12 invariant).
        assertEquals(700L, BinMath.binLiquidity(100, 200, new BigDecimal("5")));   // 500 + 200
        assertEquals(200L, BinMath.binLiquidity(0, 200, new BigDecimal("5")));     // pure-Y → reserveY
        assertEquals(2900L, BinMath.binLiquidity(10, 0, new BigDecimal("290")));   // pure-X at SBER-like price
        assertEquals(0L, BinMath.binLiquidity(0, 0, new BigDecimal("5")));         // empty bin
        assertEquals(50L, BinMath.binLiquidity(0, 50, null));                       // null price, no X
    }
}
