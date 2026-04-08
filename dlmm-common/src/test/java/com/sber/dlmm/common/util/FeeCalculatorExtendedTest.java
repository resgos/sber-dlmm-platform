package com.sber.dlmm.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended tests for FeeCalculator covering overflow safety, boundary conditions,
 * real-world parameter ranges, and fee economics invariants.
 */
class FeeCalculatorExtendedTest {

    // ── calculateSwapFee: boundary & invariants ────────────────────

    @Nested
    @DisplayName("calculateSwapFee: boundary & invariants")
    class SwapFeeBoundaryTests {

        @Test
        @DisplayName("fee is always non-negative")
        void alwaysNonNegative() {
            for (int va = 0; va <= 10_000; va += 500) {
                for (int bps = 0; bps <= 100; bps += 10) {
                    long fee = FeeCalculator.calculateSwapFee(1_000_000, bps, va, 100);
                    assertTrue(fee >= 0,
                            "Fee should be >= 0 for bps=" + bps + ", VA=" + va + ", got " + fee);
                }
            }
        }

        @Test
        @DisplayName("fee never exceeds amountIn")
        void neverExceedsAmount() {
            long amountIn = 1_000_000;
            // Extreme params: max baseFeeBps=100 (1%), VA=10000, binStep=500
            long fee = FeeCalculator.calculateSwapFee(amountIn, 100, 10_000, 500);
            assertTrue(fee <= amountIn,
                    "Fee " + fee + " should not exceed amountIn " + amountIn);
        }

        @Test
        @DisplayName("base fee scales linearly with amountIn")
        void baseFeeLinearScale() {
            int bps = 30;
            long fee1 = FeeCalculator.calculateSwapFee(1_000_000, bps, 0, 10);
            long fee2 = FeeCalculator.calculateSwapFee(2_000_000, bps, 0, 10);
            assertEquals(fee1 * 2, fee2, "Base fee should scale linearly");
        }

        @Test
        @DisplayName("variable fee grows quadratically with VA")
        void variableFeeQuadratic() {
            long amountIn = 1_000_000_000L;
            int binStep = 100;

            long fee100 = FeeCalculator.calculateSwapFee(amountIn, 0, 100, binStep);
            long fee200 = FeeCalculator.calculateSwapFee(amountIn, 0, 200, binStep);
            long fee400 = FeeCalculator.calculateSwapFee(amountIn, 0, 400, binStep);

            // VA doubles → fee should ~quadruple (within integer rounding)
            if (fee100 > 0) {
                double ratio1 = (double) fee200 / fee100;
                assertTrue(ratio1 >= 3.5 && ratio1 <= 4.5,
                        "Doubling VA should ~4x fee. Ratio: " + ratio1);
            }
        }

        @ParameterizedTest
        @DisplayName("exact base fee calculation for various bps")
        @CsvSource({
                "1000000, 10, 1000",
                "1000000, 30, 3000",
                "1000000, 50, 5000",
                "1000000, 100, 10000",
                "10000000, 25, 25000",
                "500000, 1, 50"
        })
        void exactBaseFee(long amountIn, int bps, long expectedFee) {
            long fee = FeeCalculator.calculateSwapFee(amountIn, bps, 0, 10);
            assertEquals(expectedFee, fee,
                    "Base fee for " + amountIn + " at " + bps + " bps should be " + expectedFee);
        }

        @Test
        @DisplayName("large amountIn (1 trillion) does not overflow")
        void largeAmountNoOverflow() {
            long amountIn = 1_000_000_000_000L;
            long fee = FeeCalculator.calculateSwapFee(amountIn, 30, 1000, 100);
            assertTrue(fee > 0, "Fee should be positive for 1T amount");
            assertTrue(fee < amountIn, "Fee should be less than amount");
        }
    }

    // ── updateVolatilityAccumulator: extended ──────────────────────

    @Nested
    @DisplayName("updateVolatilityAccumulator: extended")
    class UpdateVAExtendedTests {

        @Test
        @DisplayName("multiple updates accumulate correctly")
        void multipleUpdates() {
            int va = 0;
            va = FeeCalculator.updateVolatilityAccumulator(va, 3, 10_000);
            assertEquals(3, va);
            va = FeeCalculator.updateVolatilityAccumulator(va, 5, 10_000);
            assertEquals(8, va);
            va = FeeCalculator.updateVolatilityAccumulator(va, 2, 10_000);
            assertEquals(10, va);
        }

        @Test
        @DisplayName("rapid capping: many small crosses can reach max")
        void rapidCapping() {
            int va = 0;
            int max = 100;
            for (int i = 0; i < 200; i++) {
                va = FeeCalculator.updateVolatilityAccumulator(va, 1, max);
            }
            assertEquals(max, va, "VA should be capped at max after many updates");
        }

        @Test
        @DisplayName("large single jump caps correctly")
        void largeSingleJump() {
            int result = FeeCalculator.updateVolatilityAccumulator(0, 50_000, 10_000);
            assertEquals(10_000, result);
        }
    }

    // ── decayVolatilityAccumulator: extended ───────────────────────

    @Nested
    @DisplayName("decayVolatilityAccumulator: extended")
    class DecayVAExtendedTests {

        @Test
        @DisplayName("repeated decay converges to zero")
        void repeatedDecayConvergesToZero() {
            int va = 10_000;
            int decayRate = 1000; // 10% per step
            for (int i = 0; i < 100; i++) {
                va = FeeCalculator.decayVolatilityAccumulator(va, decayRate);
            }
            assertEquals(0, va, "VA should decay to 0 after many steps");
        }

        @Test
        @DisplayName("1% decay rate: value after 10 steps")
        void onePercentDecay() {
            int va = 10_000;
            int decayRate = 100; // 1%
            for (int i = 0; i < 10; i++) {
                va = FeeCalculator.decayVolatilityAccumulator(va, decayRate);
            }
            // (10000 * 0.99^10) ≈ 9043.82 → integer 9043-ish (depends on rounding)
            assertTrue(va >= 9000 && va <= 9100,
                    "After 10 steps of 1% decay from 10000, VA should be ~9044, got " + va);
        }

        @ParameterizedTest
        @DisplayName("decay rate boundary values")
        @CsvSource({
                "1000, 0, 1000",     // 0% decay → no change
                "1000, 5000, 500",   // 50% decay → half
                "1000, 10000, 0",    // 100% decay → zero
                "0, 5000, 0"         // zero VA stays zero
        })
        void decayBoundaries(int va, int decayRate, int expected) {
            int result = FeeCalculator.decayVolatilityAccumulator(va, decayRate);
            assertEquals(expected, result);
        }
    }

    // ── Fee economics: integrated scenarios ────────────────────────

    @Nested
    @DisplayName("Fee economics: integrated scenarios")
    class FeeEconomicsTests {

        @Test
        @DisplayName("calm market: low VA → fee is approximately base fee only")
        void calmMarket() {
            long amountIn = 50_000_000_000L; // 50B SRUB
            int baseFeeBps = 25; // 0.25%
            int va = 10; // very calm

            long fee = FeeCalculator.calculateSwapFee(amountIn, baseFeeBps, va, 100);
            long baseFee = amountIn * baseFeeBps / 10_000;

            // Variable fee should be negligible
            assertTrue(fee - baseFee < baseFee / 100,
                    "In calm market, variable fee should be < 1% of base fee");
        }

        @Test
        @DisplayName("volatile market: high VA → variable fee adds surcharge above base fee")
        void volatileMarket() {
            long amountIn = 50_000_000_000L;
            int baseFeeBps = 25;
            int va = 5000; // very volatile

            long totalFee = FeeCalculator.calculateSwapFee(amountIn, baseFeeBps, va, 100);
            long baseFee = amountIn * baseFeeBps / 10_000;

            assertTrue(totalFee > baseFee,
                    "In volatile market, total fee (" + totalFee + ") should be > base fee (" + baseFee + ")");
        }

        @Test
        @DisplayName("fee after spike then decay is lower than during spike")
        void spikeAndDecay() {
            long amountIn = 1_000_000_000L;
            int baseFeeBps = 30;
            int binStep = 100;

            // Spike: 50 bins crossed
            int va = FeeCalculator.updateVolatilityAccumulator(0, 50, 10_000);
            long feeDuringSpike = FeeCalculator.calculateSwapFee(amountIn, baseFeeBps, va, binStep);

            // Decay 5 times (simulating time passing)
            for (int i = 0; i < 5; i++) {
                va = FeeCalculator.decayVolatilityAccumulator(va, 2000); // 20% per step
            }
            long feeAfterDecay = FeeCalculator.calculateSwapFee(amountIn, baseFeeBps, va, binStep);

            assertTrue(feeAfterDecay <= feeDuringSpike,
                    "Fee after decay (" + feeAfterDecay + ") should be <= fee during spike (" + feeDuringSpike + ")");
        }
    }
}
