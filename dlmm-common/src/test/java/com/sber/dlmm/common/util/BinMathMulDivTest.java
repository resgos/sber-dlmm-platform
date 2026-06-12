package com.sber.dlmm.common.util;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit B6 regression — pins {@link BinMath#mulDiv} against the long-overflow
 * that produced NEGATIVE position values live (2026-06-12).
 *
 * <p>The engine's pro-rata split {@code reserve · shares / liquidity} was raw
 * {@code long} math; after the ×10⁴ amount rescale the product reaches ~2.8e23
 * (≫ {@code Long.MAX_VALUE} ≈ 9.2e18) and wraps. The constants below are the
 * actual live values from pool SUSDT/SRUB bin 8388608 / position
 * {@code 77000000-…-008} where the API showed {@code currentValueX = -1 243 132}.
 */
class BinMathMulDivTest {

    // Live repro values (pool c0…110, bin 8388608, position 77…008).
    private static final long RESERVE_X = 180_487_065_163L;
    private static final long RESERVE_Y = 105_279_466_481L;
    private static final long SHARES = 15_909_090_000L;
    private static final long LIQUIDITY = 13_083_689_202_102L;

    @Test
    void rawLongMathOverflowsOnLiveMagnitudes() {
        // Documents WHY mulDiv exists: the pre-fix expression wraps negative.
        long raw = RESERVE_X * SHARES / LIQUIDITY;
        assertTrue(raw < 0, "expected the raw long product to overflow negative, was " + raw);
    }

    @Test
    void mulDivMatchesBigIntegerFloorOnLiveMagnitudes() {
        long expectedX = BigInteger.valueOf(RESERVE_X).multiply(BigInteger.valueOf(SHARES))
                .divide(BigInteger.valueOf(LIQUIDITY)).longValueExact();
        long expectedY = BigInteger.valueOf(RESERVE_Y).multiply(BigInteger.valueOf(SHARES))
                .divide(BigInteger.valueOf(LIQUIDITY)).longValueExact();

        assertEquals(expectedX, BinMath.mulDiv(RESERVE_X, SHARES, LIQUIDITY));
        assertEquals(expectedY, BinMath.mulDiv(RESERVE_Y, SHARES, LIQUIDITY));
        assertTrue(BinMath.mulDiv(RESERVE_X, SHARES, LIQUIDITY) > 0, "value share must be positive");
        // A position's slice can never exceed the bin's whole reserve (shares <= liquidity here).
        assertTrue(BinMath.mulDiv(RESERVE_X, SHARES, LIQUIDITY) <= RESERVE_X);
    }

    @Test
    void mulDivKeepsExactSmallMagnitudeSemantics() {
        // Same floor semantics as the old long math when nothing overflows.
        assertEquals(33L, BinMath.mulDiv(100, 1, 3));
        assertEquals(100L * 7 / 9, BinMath.mulDiv(100, 7, 9));
        assertEquals(0L, BinMath.mulDiv(0, 5, 7));
        assertEquals(0L, BinMath.mulDiv(5, 0, 7));
        assertEquals(0L, BinMath.mulDiv(5, 7, 0));
        assertEquals(0L, BinMath.mulDiv(-5, 7, 9));
    }
}
