package com.sber.dlmm.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeeCalculatorTest {

    // ── calculateSwapFee ────────────────────────────────────────

    @Nested
    @DisplayName("calculateSwapFee")
    class CalculateSwapFeeTests {

        @Test
        @DisplayName("base fee only when volatilityAccumulator is zero")
        void baseFeeOnly() {
            long amountIn = 1_000_000;
            int baseFeeBps = 30; // 0.3%
            int volatilityAccumulator = 0;
            int binStep = 10;

            long fee = FeeCalculator.calculateSwapFee(amountIn, baseFeeBps, volatilityAccumulator, binStep);

            // baseFee = 1_000_000 * 30 / 10_000 = 3_000
            // variableFee = 0
            assertEquals(3_000, fee);
        }

        @Test
        @DisplayName("includes variable fee when volatilityAccumulator > 0")
        void basePlusVariableFee() {
            long amountIn = 10_000_000;
            int baseFeeBps = 30;
            int volatilityAccumulator = 100;
            int binStep = 20;

            long fee = FeeCalculator.calculateSwapFee(amountIn, baseFeeBps, volatilityAccumulator, binStep);

            // baseFee = 10_000_000 * 30 / 10_000 = 30_000
            // variableFee intermediate = 100 * 100 * 20 / 10_000_000_000 = 200_000 / 10_000_000_000 = 0 (long division)
            // So variableFee = 10_000_000 * 0 / 10_000 = 0
            // Total = 30_000
            // (small VA => variable fee rounds to 0)
            long baseFee = amountIn * baseFeeBps / 10_000;
            long varIntermediate = (long) volatilityAccumulator * volatilityAccumulator * binStep / 10_000_000_000L;
            long variableFee = amountIn * varIntermediate / 10_000;
            assertEquals(baseFee + variableFee, fee);
        }

        @Test
        @DisplayName("high volatility produces significant variable fee")
        void highVolatility() {
            long amountIn = 100_000_000;
            int baseFeeBps = 10;
            int volatilityAccumulator = 10_000;
            int binStep = 100;

            long fee = FeeCalculator.calculateSwapFee(amountIn, baseFeeBps, volatilityAccumulator, binStep);

            // baseFee = 100_000_000 * 10 / 10_000 = 100_000
            // varIntermediate = 10_000 * 10_000 * 100 / 10_000_000_000 = 10_000_000_000 / 10_000_000_000 = 1
            // variableFee = 100_000_000 * 1 / 10_000 = 10_000
            // total = 110_000
            assertEquals(110_000, fee);
        }

        /**
         * Spec 14.1: testCalculateSwapFee — amountIn=1_000_000, baseFee=30bps, VA=0, binStep=10
         * Expected: baseFee=3000, variableFee=0 → total=3000
         */
        @Test
        @DisplayName("testCalculateSwapFee: amountIn=1M, baseFee=30bps, VA=0 → 3000")
        void testCalculateSwapFee() {
            long amountIn = 1_000_000;
            int baseFeeBps = 30;
            int volatilityAccumulator = 0;
            int binStep = 10;

            long fee = FeeCalculator.calculateSwapFee(amountIn, baseFeeBps, volatilityAccumulator, binStep);

            // baseFee = 1_000_000 * 30 / 10_000 = 3_000
            // variableFee = 0 (VA is 0)
            assertEquals(3_000, fee, "Swap fee should be 3000 for baseFee=30bps on 1M with VA=0");

            // Also test with non-zero VA for variable fee component
            long feeWithVA = FeeCalculator.calculateSwapFee(100_000_000, 10, 10_000, 100);
            // baseFee = 100_000_000 * 10 / 10_000 = 100_000
            // varIntermediate = 10_000 * 10_000 * 100 / 10_000_000_000 = 1
            // variableFee = 100_000_000 * 1 / 10_000 = 10_000
            assertEquals(110_000, feeWithVA, "Swap fee should include variable fee with non-zero VA");
        }

        @Test
        @DisplayName("zero amount returns zero fee")
        void zeroAmount() {
            long fee = FeeCalculator.calculateSwapFee(0, 30, 100, 10);
            assertEquals(0, fee);
        }

        @Test
        @DisplayName("zero baseFeeBps with zero VA returns zero")
        void zeroBaseFeeZeroVA() {
            long fee = FeeCalculator.calculateSwapFee(1_000_000, 0, 0, 10);
            assertEquals(0, fee);
        }

        @Test
        @DisplayName("fee is always less than amountIn")
        void feeLessThanAmount() {
            long amountIn = 1_000_000;
            long fee = FeeCalculator.calculateSwapFee(amountIn, 100, 500, 50);
            assertTrue(fee < amountIn, "Fee " + fee + " should be less than amountIn " + amountIn);
        }
    }

    // ── updateVolatilityAccumulator ─────────────────────────────

    @Nested
    @DisplayName("updateVolatilityAccumulator")
    class UpdateVolatilityAccumulatorTests {

        @Test
        @DisplayName("adds absolute binsCrossed to current VA")
        void addsBinsCrossed() {
            int result = FeeCalculator.updateVolatilityAccumulator(100, 5, 10_000);
            assertEquals(105, result);
        }

        @Test
        @DisplayName("handles negative binsCrossed (uses absolute value)")
        void negativeBinsCrossed() {
            int result = FeeCalculator.updateVolatilityAccumulator(100, -3, 10_000);
            assertEquals(103, result);
        }

        @Test
        @DisplayName("caps at maxVolatility")
        void capsAtMax() {
            int result = FeeCalculator.updateVolatilityAccumulator(9_990, 20, 10_000);
            assertEquals(10_000, result);
        }

        @Test
        @DisplayName("already at max stays at max")
        void alreadyAtMax() {
            int result = FeeCalculator.updateVolatilityAccumulator(10_000, 5, 10_000);
            assertEquals(10_000, result);
        }

        @Test
        @DisplayName("zero binsCrossed returns current VA")
        void zeroBinsCrossed() {
            int result = FeeCalculator.updateVolatilityAccumulator(42, 0, 10_000);
            assertEquals(42, result);
        }

        /**
         * Spec 14.1: testUpdateVolatilityAccumulator — 0 bins → VA unchanged; 5 bins → VA+5
         */
        @Test
        @DisplayName("testUpdateVolatilityAccumulator: 0 bins → VA unchanged, 5 bins → VA+5")
        void testUpdateVolatilityAccumulator() {
            int currentVA = 100;
            int maxVolatility = 10_000;

            // 0 bins crossed → VA unchanged
            int result0 = FeeCalculator.updateVolatilityAccumulator(currentVA, 0, maxVolatility);
            assertEquals(currentVA, result0, "VA should remain unchanged when 0 bins crossed");

            // 5 bins crossed → VA + 5
            int result5 = FeeCalculator.updateVolatilityAccumulator(currentVA, 5, maxVolatility);
            assertEquals(105, result5, "VA should increase by 5 when 5 bins crossed");
        }
    }

    // ── decayVolatilityAccumulator ──────────────────────────────

    @Nested
    @DisplayName("decayVolatilityAccumulator")
    class DecayVolatilityAccumulatorTests {

        @Test
        @DisplayName("decays by (10000 - decayRate) / 10000")
        void normalDecay() {
            // decayRate = 1000 means 10% decay
            // currentVA * (10000 - 1000) / 10000 = 500 * 9000 / 10000 = 450
            int result = FeeCalculator.decayVolatilityAccumulator(500, 1000);
            assertEquals(450, result);
        }

        @Test
        @DisplayName("100% decay rate returns 0")
        void fullDecay() {
            int result = FeeCalculator.decayVolatilityAccumulator(500, 10_000);
            assertEquals(0, result);
        }

        @Test
        @DisplayName("0% decay rate returns same value")
        void noDecay() {
            int result = FeeCalculator.decayVolatilityAccumulator(500, 0);
            assertEquals(500, result);
        }

        @Test
        @DisplayName("result is never negative")
        void neverNegative() {
            int result = FeeCalculator.decayVolatilityAccumulator(0, 5000);
            assertEquals(0, result);
        }

        @Test
        @DisplayName("small decay on small value floors to integer")
        void integerFlooring() {
            // 1 * (10000 - 1) / 10000 = 1 * 9999 / 10000 = 0 (integer division)
            int result = FeeCalculator.decayVolatilityAccumulator(1, 1);
            assertEquals(0, result);
        }

        /**
         * Spec 14.1: testDecay — VA=100, decayRate=1000 (10%) → VA=90
         * Formula: 100 * (10000 - 1000) / 10000 = 100 * 9000 / 10000 = 90
         */
        @Test
        @DisplayName("testDecay: VA=100, decayRate=1000 → VA=90")
        void testDecay() {
            int currentVA = 100;
            int decayRate = 1000; // 10% decay

            int result = FeeCalculator.decayVolatilityAccumulator(currentVA, decayRate);
            assertEquals(90, result, "VA=100 with 10% decay should yield 90");
        }
    }
}
