package com.sber.dlmm.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended tests for BinMath covering edge cases, precision, stress scenarios,
 * and real DLMM-pool-like parameters (SBTC/SRUB with basePrice=5M, binStep=100).
 */
class BinMathExtendedTest {

    // ── binPrice: precision & monotonicity ─────────────────────────

    @Nested
    @DisplayName("binPrice: precision & monotonicity")
    class BinPricePrecisionTests {

        @Test
        @DisplayName("price is strictly monotonically increasing with binId")
        void priceMonotonicallyIncreases() {
            BigDecimal base = new BigDecimal("5000000.0");
            int binStep = 100;
            BigDecimal prev = BinMath.binPrice(base, binStep, -50);
            for (int i = -49; i <= 50; i++) {
                BigDecimal current = BinMath.binPrice(base, binStep, i);
                assertTrue(current.compareTo(prev) > 0,
                        "Price at bin " + i + " (" + current + ") should be > price at bin " + (i - 1) + " (" + prev + ")");
                prev = current;
            }
        }

        @Test
        @DisplayName("symmetric: price at +n and -n are reciprocal around basePrice")
        void symmetricAroundBase() {
            BigDecimal base = new BigDecimal("100.0");
            int binStep = 50; // factor = 1.005
            for (int n = 1; n <= 20; n++) {
                BigDecimal up = BinMath.binPrice(base, binStep, n);
                BigDecimal down = BinMath.binPrice(base, binStep, -n);
                // up * down should ≈ base^2
                BigDecimal product = up.multiply(down);
                BigDecimal baseSq = base.multiply(base);
                BigDecimal ratio = product.divide(baseSq, 10, RoundingMode.HALF_UP);
                assertTrue(ratio.subtract(BigDecimal.ONE).abs().compareTo(new BigDecimal("0.001")) < 0,
                        "P(+" + n + ")*P(-" + n + ")/base² should ≈ 1.0, got " + ratio);
            }
        }

        @ParameterizedTest
        @DisplayName("binStep parameter boundaries")
        @ValueSource(ints = {1, 10, 100, 500, 1000, 5000})
        void variousBinSteps(int binStep) {
            BigDecimal base = BigDecimal.ONE;
            BigDecimal p = BinMath.binPrice(base, binStep, 1);
            BigDecimal expectedFactor = BigDecimal.ONE.add(new BigDecimal(binStep).divide(new BigDecimal("10000")));
            assertEquals(0, p.setScale(10, RoundingMode.HALF_UP).compareTo(
                    expectedFactor.setScale(10, RoundingMode.HALF_UP)),
                    "P(1) should equal 1 + binStep/10000 for binStep=" + binStep);
        }

        @Test
        @DisplayName("large binId (1000) does not throw and produces valid result")
        void largeBinId() {
            BigDecimal base = BigDecimal.ONE;
            BigDecimal result = BinMath.binPrice(base, 10, 1000);
            assertNotNull(result);
            assertTrue(result.compareTo(BigDecimal.ONE) > 0);
            assertEquals(18, result.scale());
        }

        @Test
        @DisplayName("SBTC/SRUB real pool: basePrice=5M, binStep=100")
        void realPoolParams() {
            BigDecimal base = new BigDecimal("5000000.0");
            int binStep = 100; // 1% per bin

            BigDecimal p0 = BinMath.binPrice(base, binStep, 0);
            assertEquals(0, p0.compareTo(base.setScale(18, RoundingMode.HALF_UP)));

            BigDecimal p1 = BinMath.binPrice(base, binStep, 1);
            BigDecimal expected1 = new BigDecimal("5050000.000000000000000000");
            assertEquals(0, p1.compareTo(expected1), "P(1) should be 5M * 1.01 = 5.05M");

            BigDecimal pNeg1 = BinMath.binPrice(base, binStep, -1);
            // 5000000 / 1.01 ≈ 4950495.049505...
            assertTrue(pNeg1.compareTo(new BigDecimal("4950495")) > 0);
            assertTrue(pNeg1.compareTo(new BigDecimal("4950496")) < 0);
        }
    }

    // ── priceToBinId: extended ──────────────────────────────────────

    @Nested
    @DisplayName("priceToBinId: extended")
    class PriceToBinIdExtendedTests {

        @ParameterizedTest
        @DisplayName("round-trip for various binIds")
        @ValueSource(ints = {-100, -50, -10, -1, 0, 1, 10, 50, 100})
        void roundTripParameterized(int binId) {
            BigDecimal base = new BigDecimal("5000000.0");
            int binStep = 100;
            BigDecimal price = BinMath.binPrice(base, binStep, binId);
            int recovered = BinMath.priceToBinId(base, binStep, price);
            assertEquals(binId, recovered, "Round-trip failed for binId=" + binId);
        }

        @ParameterizedTest
        @DisplayName("round-trip for various binSteps")
        @CsvSource({"1,5", "10,20", "50,10", "100,50", "500,3"})
        void roundTripVariousSteps(int binStep, int binId) {
            BigDecimal base = new BigDecimal("100.0");
            BigDecimal price = BinMath.binPrice(base, binStep, binId);
            int recovered = BinMath.priceToBinId(base, binStep, price);
            assertEquals(binId, recovered,
                    "Round-trip failed for binStep=" + binStep + ", binId=" + binId);
        }

        @Test
        @DisplayName("price slightly above base rounds to binId 0 or 1")
        void slightlyAboveBase() {
            BigDecimal base = new BigDecimal("100.0");
            int binStep = 100; // factor 1.01, so bin 1 = 101.0
            BigDecimal slightlyAbove = new BigDecimal("100.4"); // closer to bin 0
            int result = BinMath.priceToBinId(base, binStep, slightlyAbove);
            assertEquals(0, result, "100.4 should round to bin 0 (midpoint is ~100.5)");
        }

        @Test
        @DisplayName("throws for zero price")
        void throwsZeroPrice() {
            assertThrows(IllegalArgumentException.class,
                    () -> BinMath.priceToBinId(BigDecimal.ONE, 100, BigDecimal.ZERO));
        }
    }

    // ── compositionFactor: extended ─────────────────────────────────

    @Nested
    @DisplayName("compositionFactor: extended")
    class CompositionFactorExtendedTests {

        @Test
        @DisplayName("result is always between 0 and 1 inclusive")
        void alwaysBetweenZeroAndOne() {
            for (long reserveY = 0; reserveY <= 1000; reserveY += 100) {
                for (long liquidity = Math.max(reserveY, 1); liquidity <= 1000; liquidity += 100) {
                    BigDecimal cf = BinMath.compositionFactor(reserveY, liquidity);
                    assertTrue(cf.compareTo(BigDecimal.ZERO) >= 0,
                            "CF should be >= 0 for reserveY=" + reserveY + ", liquidity=" + liquidity);
                    assertTrue(cf.compareTo(BigDecimal.ONE) <= 0,
                            "CF should be <= 1 for reserveY=" + reserveY + ", liquidity=" + liquidity);
                }
            }
        }

        @Test
        @DisplayName("precision: 1/3 has at least 15 correct digits")
        void precisionOneThird() {
            BigDecimal cf = BinMath.compositionFactor(1, 3);
            BigDecimal expected = new BigDecimal("0.333333333333333");
            assertTrue(cf.subtract(expected).abs().compareTo(new BigDecimal("0.000000000000001")) < 0,
                    "1/3 should have 15+ digits of precision, got " + cf);
        }

        @Test
        @DisplayName("stress: billion-scale values")
        void billionScale() {
            long reserveY = 999_999_999_999L;
            long liquidity = 1_000_000_000_000L;
            BigDecimal cf = BinMath.compositionFactor(reserveY, liquidity);
            assertTrue(cf.compareTo(new BigDecimal("0.999")) > 0);
            assertTrue(cf.compareTo(BigDecimal.ONE) < 0);
        }
    }

    // ── binPriceAtBin: absolute-binId helper (Sprint 9-DS-r4, P0-2) ─

    @Nested
    @DisplayName("binPriceAtBin: absolute-binId helper")
    class BinPriceAtBinTests {

        // Anchored at LB-DLMM's 2^23 convention. The bug this helper
        // exists to prevent: callers used to write
        //   BinMath.binPrice(base, step, binId)
        // when they meant
        //   BinMath.binPrice(base, step, binId - activeBinId)
        // — raising the factor to ~8.4M and silently overflowing.
        private static final int ACTIVE = 1 << 23; // 8_388_608

        @Test
        @DisplayName("at the active bin, price equals basePrice")
        void atActiveBin_returnsBasePrice() {
            BigDecimal base = new BigDecimal("5000000.0");
            BigDecimal price = BinMath.binPriceAtBin(base, 100, ACTIVE, ACTIVE);
            assertEquals(0, price.compareTo(base.setScale(18, RoundingMode.HALF_UP)));
        }

        @Test
        @DisplayName("one bin above active = basePrice * (1 + step/10000)")
        void oneAbove_appliesOneFactor() {
            BigDecimal base = new BigDecimal("5000000.0");
            int binStep = 100;
            BigDecimal expected = base
                    .multiply(new BigDecimal("1.01"))
                    .setScale(18, RoundingMode.HALF_UP);
            BigDecimal actual = BinMath.binPriceAtBin(base, binStep, ACTIVE + 1, ACTIVE);
            assertEquals(0, actual.compareTo(expected),
                    "expected " + expected + " but got " + actual);
        }

        @Test
        @DisplayName("one bin below active = basePrice / (1 + step/10000)")
        void oneBelow_dividesOneFactor() {
            BigDecimal base = new BigDecimal("5000000.0");
            int binStep = 100;
            BigDecimal expected = base
                    .divide(new BigDecimal("1.01"),
                            new java.math.MathContext(34))
                    .setScale(18, RoundingMode.HALF_UP);
            BigDecimal actual = BinMath.binPriceAtBin(base, binStep, ACTIVE - 1, ACTIVE);
            assertEquals(0, actual.compareTo(expected),
                    "expected " + expected + " but got " + actual);
        }

        @Test
        @DisplayName("equivalent to binPrice(base, step, binId - activeBinId)")
        void delegatesToBinPriceWithOffset() {
            BigDecimal base = new BigDecimal("12345.6789");
            int binStep = 25;
            for (int offset = -50; offset <= 50; offset += 7) {
                BigDecimal viaHelper = BinMath.binPriceAtBin(base, binStep, ACTIVE + offset, ACTIVE);
                BigDecimal viaRaw = BinMath.binPrice(base, binStep, offset);
                assertEquals(0, viaHelper.compareTo(viaRaw),
                        "Mismatch at offset " + offset
                                + ": helper=" + viaHelper + " raw=" + viaRaw);
            }
        }

        @Test
        @DisplayName("with activeBinId=0, behaves identically to legacy binPrice")
        void zeroActive_isLegacyBehaviour() {
            BigDecimal base = new BigDecimal("100.0");
            for (int binId = -10; binId <= 10; binId++) {
                BigDecimal viaHelper = BinMath.binPriceAtBin(base, 50, binId, 0);
                BigDecimal viaRaw = BinMath.binPrice(base, 50, binId);
                assertEquals(0, viaHelper.compareTo(viaRaw),
                        "Mismatch at binId " + binId);
            }
        }
    }
}
