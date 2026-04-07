package com.sber.dlmm.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinMathTest {

    // ── binPrice ────────────────────────────────────────────────

    @Nested
    @DisplayName("binPrice")
    class BinPriceTests {

        @Test
        @DisplayName("binId=0 returns basePrice unchanged")
        void binPriceAtZero() {
            BigDecimal base = new BigDecimal("1.000000000000000000");
            BigDecimal result = BinMath.binPrice(base, 10, 0);
            assertEquals(0, result.compareTo(base));
        }

        /**
         * Spec 14.1: testBinPrice — P(0)=1.0, binStep=100 → P(1)=1.01, P(10)≈1.10462
         */
        @Test
        @DisplayName("testBinPrice: P(0)=1.0, binStep=100 → P(1)=1.01")
        void testBinPrice_bin1() {
            BigDecimal base = BigDecimal.ONE;
            int binStep = 100; // factor = 1 + 100/10000 = 1.01

            // P(0) should equal base price
            BigDecimal p0 = BinMath.binPrice(base, binStep, 0);
            assertEquals(0, p0.compareTo(BigDecimal.ONE.setScale(18, RoundingMode.HALF_UP)),
                    "P(0) should be 1.0, got " + p0);

            // P(1) = 1.0 * 1.01 = 1.01
            BigDecimal p1 = BinMath.binPrice(base, binStep, 1);
            BigDecimal expected1 = new BigDecimal("1.010000000000000000");
            assertEquals(0, p1.compareTo(expected1),
                    "P(1) should be 1.01, got " + p1);
        }

        @Test
        @DisplayName("testBinPrice: P(0)=1.0, binStep=100 → P(10)≈1.10462")
        void testBinPrice_bin10() {
            BigDecimal base = BigDecimal.ONE;
            int binStep = 100; // factor = 1.01

            // P(10) = 1.01^10 ≈ 1.10462212541120451
            BigDecimal p10 = BinMath.binPrice(base, binStep, 10);
            BigDecimal expectedLow = new BigDecimal("1.1046");
            BigDecimal expectedHigh = new BigDecimal("1.1047");
            assertTrue(p10.compareTo(expectedLow) >= 0 && p10.compareTo(expectedHigh) <= 0,
                    "P(10) should be ≈1.10462, got " + p10);
        }

        @Test
        @DisplayName("positive binId multiplies by factor iteratively")
        void binPricePositive() {
            BigDecimal base = new BigDecimal("1.000000000000000000");
            int binStep = 100; // factor = 1.01
            int binId = 3;
            // Expected: 1.0 * 1.01^3 = 1.030301
            BigDecimal expected = new BigDecimal("1.030301000000000000");
            BigDecimal result = BinMath.binPrice(base, binStep, binId);
            assertEquals(0, result.compareTo(expected),
                    "Expected " + expected + " but got " + result);
        }

        @Test
        @DisplayName("negative binId divides by factor iteratively")
        void binPriceNegative() {
            BigDecimal base = new BigDecimal("100.000000000000000000");
            int binStep = 100; // factor = 1.01
            int binId = -2;
            // Expected: 100 / 1.01 / 1.01 = 100 / 1.0201 ≈ 98.029604940...
            BigDecimal factor = new BigDecimal("1.01");
            BigDecimal expected = base.divide(factor, 128, RoundingMode.HALF_UP)
                    .divide(factor, 128, RoundingMode.HALF_UP)
                    .setScale(18, RoundingMode.HALF_UP);
            BigDecimal result = BinMath.binPrice(base, binStep, binId);
            assertEquals(0, result.compareTo(expected),
                    "Expected " + expected + " but got " + result);
        }

        @Test
        @DisplayName("binStep=1 gives very small price increments")
        void binPriceSmallStep() {
            BigDecimal base = new BigDecimal("50.000000000000000000");
            int binStep = 1; // factor = 1.0001
            BigDecimal result = BinMath.binPrice(base, binStep, 1);
            // Expected: 50 * 1.0001 = 50.005
            BigDecimal expected = new BigDecimal("50.005000000000000000");
            assertEquals(0, result.compareTo(expected),
                    "Expected " + expected + " but got " + result);
        }

        @Test
        @DisplayName("large binStep gives larger price moves")
        void binPriceLargeStep() {
            BigDecimal base = BigDecimal.ONE;
            int binStep = 5000; // factor = 1.5
            BigDecimal result = BinMath.binPrice(base, binStep, 2);
            // Expected: 1.0 * 1.5 * 1.5 = 2.25
            BigDecimal expected = new BigDecimal("2.250000000000000000");
            assertEquals(0, result.compareTo(expected),
                    "Expected " + expected + " but got " + result);
        }

        @Test
        @DisplayName("result always has scale 18")
        void binPriceScale() {
            BigDecimal result = BinMath.binPrice(BigDecimal.TEN, 10, 5);
            assertEquals(18, result.scale());
        }
    }

    // ── priceToBinId ────────────────────────────────────────────

    @Nested
    @DisplayName("priceToBinId")
    class PriceToBinIdTests {

        @Test
        @DisplayName("same price as base returns binId 0")
        void priceToBinIdZero() {
            BigDecimal base = new BigDecimal("10.0");
            int result = BinMath.priceToBinId(base, 100, base);
            assertEquals(0, result);
        }

        /**
         * Spec 14.1: testPriceToBinId — inverse function round-trip
         */
        @Test
        @DisplayName("testPriceToBinId: round-trip binPrice → priceToBinId recovers binId")
        void testPriceToBinId() {
            BigDecimal base = new BigDecimal("1.0");
            int binStep = 100;

            // Forward: compute price for binId=10
            BigDecimal priceAt10 = BinMath.binPrice(base, binStep, 10);
            // Inverse: should recover binId=10
            int computedBinId = BinMath.priceToBinId(base, binStep, priceAt10);
            assertEquals(10, computedBinId, "Round-trip should recover binId 10");

            // Also verify for binId=1
            BigDecimal priceAt1 = BinMath.binPrice(base, binStep, 1);
            int computedBinId1 = BinMath.priceToBinId(base, binStep, priceAt1);
            assertEquals(1, computedBinId1, "Round-trip should recover binId 1");
        }

        @Test
        @DisplayName("round-trip: binPrice -> priceToBinId gives back original binId")
        void roundTrip() {
            BigDecimal base = new BigDecimal("1.0");
            int binStep = 25;
            int binId = 42;
            BigDecimal price = BinMath.binPrice(base, binStep, binId);
            int computed = BinMath.priceToBinId(base, binStep, price);
            assertEquals(binId, computed, "Round-trip should recover binId");
        }

        @Test
        @DisplayName("round-trip works for negative binId")
        void roundTripNegative() {
            BigDecimal base = new BigDecimal("100.0");
            int binStep = 50;
            int binId = -15;
            BigDecimal price = BinMath.binPrice(base, binStep, binId);
            int computed = BinMath.priceToBinId(base, binStep, price);
            assertEquals(binId, computed, "Round-trip should recover negative binId");
        }

        @Test
        @DisplayName("throws IllegalArgumentException for zero basePrice")
        void throwsOnZeroBase() {
            assertThrows(IllegalArgumentException.class,
                    () -> BinMath.priceToBinId(BigDecimal.ZERO, 10, BigDecimal.ONE));
        }

        @Test
        @DisplayName("throws IllegalArgumentException for negative price")
        void throwsOnNegativePrice() {
            assertThrows(IllegalArgumentException.class,
                    () -> BinMath.priceToBinId(BigDecimal.ONE, 10, new BigDecimal("-1")));
        }
    }

    // ── compositionFactor ───────────────────────────────────────

    @Nested
    @DisplayName("compositionFactor")
    class CompositionFactorTests {

        @Test
        @DisplayName("returns zero when liquidity is zero")
        void zeroLiquidity() {
            BigDecimal result = BinMath.compositionFactor(500, 0);
            assertEquals(0, BigDecimal.ZERO.compareTo(result));
        }

        /**
         * Spec 14.1: testCompositionFactor — reserveY / liquidity
         */
        @Test
        @DisplayName("testCompositionFactor: reserveY=500, liquidity=1000 → 0.5")
        void testCompositionFactor() {
            BigDecimal result = BinMath.compositionFactor(500, 1000);
            BigDecimal expected = new BigDecimal("0.5");
            assertTrue(result.subtract(expected).abs().compareTo(new BigDecimal("0.0001")) < 0,
                    "Expected ~0.5 but got " + result);

            // Also verify edge case: all Y
            BigDecimal allY = BinMath.compositionFactor(1000, 1000);
            assertEquals(0, BigDecimal.ONE.compareTo(allY.setScale(0, RoundingMode.HALF_UP)),
                    "Should return 1.0 when reserveY equals liquidity");

            // Also verify: no Y
            BigDecimal noY = BinMath.compositionFactor(0, 1000);
            assertEquals(0, BigDecimal.ZERO.compareTo(noY),
                    "Should return 0 when reserveY is 0");
        }

        @Test
        @DisplayName("returns reserveY / liquidity")
        void normalCase() {
            BigDecimal result = BinMath.compositionFactor(500, 1000);
            BigDecimal expected = new BigDecimal("0.5");
            assertTrue(result.subtract(expected).abs().compareTo(new BigDecimal("0.0001")) < 0,
                    "Expected ~0.5 but got " + result);
        }

        @Test
        @DisplayName("returns 1.0 when reserveY equals liquidity")
        void fullY() {
            BigDecimal result = BinMath.compositionFactor(1000, 1000);
            assertEquals(0, BigDecimal.ONE.compareTo(result.setScale(0, RoundingMode.HALF_UP)));
        }

        @Test
        @DisplayName("handles large values without overflow")
        void largeValues() {
            long reserveY = 1_000_000_000_000L;
            long liquidity = 3_000_000_000_000L;
            BigDecimal result = BinMath.compositionFactor(reserveY, liquidity);
            BigDecimal expected = new BigDecimal("0.333333333333333333");
            assertTrue(result.subtract(expected).abs().compareTo(new BigDecimal("0.001")) < 0,
                    "Expected ~0.333 but got " + result);
        }
    }
}
