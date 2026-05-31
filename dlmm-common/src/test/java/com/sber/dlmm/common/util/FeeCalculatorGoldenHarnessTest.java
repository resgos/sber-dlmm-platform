package com.sber.dlmm.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STAGE 0 — characterization / golden harness for the math overhaul #14
 * (docs/MATH-OVERHAUL-PLAN-2026-05-31.md).
 *
 * <p><b>Purpose.</b> Pin the CURRENT {@code volatilityAccumulator == 0} (VA=0)
 * fee outputs across a grid of {@code (amountIn, baseFeeBps, binStep)} so that a
 * later Meteora variable-fee port (Stage 1) can be PROVEN to leave VA=0 behavior
 * byte-identical. Every seeded pool has VA=0, so these vectors are exactly the
 * live behavior — they MUST NOT change when the variable-fee shape changes.
 *
 * <p><b>The one invariant.</b> At VA=0 the variable term is 0, so
 * {@code totalFeeBps == baseFeeBps} and {@code calculateSwapFee} reduces to the
 * pure base fee {@code amountIn · baseFeeBps / 10_000}. These goldens encode that
 * and the conservation / bounds invariants (fee ≥ 0, fee ≤ amountIn,
 * totalFeeBps ≤ MAX_FEE_BPS). They are formula-INDEPENDENT for the variable term
 * by construction (they only probe VA=0), which is what makes them a safe gate.
 */
class FeeCalculatorGoldenHarnessTest {

    // ── VA=0 golden grid: total fee rate is exactly the base fee ──────────────

    @Nested
    @DisplayName("VA=0 golden: totalFeeBps == baseFeeBps (byte-identical gate)")
    class VaZeroTotalFeeBpsGoldens {

        /**
         * The whole grid of base fees × bin steps at VA=0. The variable term must
         * vanish, so totalFeeBps == baseFeeBps for EVERY combination here. binStep
         * is deliberately varied (1..500) to prove the variable term does not leak
         * in via binStep at VA=0.
         */
        @ParameterizedTest(name = "baseFeeBps={0}, binStep={1} -> totalFeeBps={0}")
        @CsvSource({
                // baseFeeBps, binStep
                "0, 1", "0, 10", "0, 100", "0, 500",
                "1, 1", "1, 10", "1, 100", "1, 500",
                "10, 1", "10, 10", "10, 100", "10, 500",
                "25, 1", "25, 10", "25, 100", "25, 500",
                "30, 1", "30, 10", "30, 100", "30, 500",
                "50, 1", "50, 10", "50, 100", "50, 500",
                "100, 1", "100, 10", "100, 100", "100, 500"
        })
        void totalFeeBpsEqualsBaseAtVaZero(int baseFeeBps, int binStep) {
            assertEquals(baseFeeBps, FeeCalculator.totalFeeBps(baseFeeBps, 0, binStep),
                    "At VA=0 the total fee rate must equal the base fee rate exactly");
        }
    }

    // ── VA=0 golden grid: calculateSwapFee == base fee on the input ───────────

    @Nested
    @DisplayName("VA=0 golden: calculateSwapFee == amountIn*baseFeeBps/10000")
    class VaZeroSwapFeeGoldens {

        /**
         * Hand-computed VA=0 fee goldens (these are the live numbers today and must
         * survive Stage 1 unchanged):
         *   fee = amountIn * baseFeeBps / 10_000   (variable term = 0 at VA=0)
         */
        @ParameterizedTest(name = "amountIn={0}, baseFeeBps={1}, binStep={2} -> fee={3}")
        @CsvSource({
                // amountIn,           baseFeeBps, binStep, expectedFee
                "1000000,              30,         10,      3000",      // 1e6 * 30 / 1e4
                "1000000,              10,         10,      1000",
                "1000000,              50,         100,     5000",
                "1000000,              100,        500,     10000",
                "10000000,             25,         20,      25000",     // 1e7 * 25 / 1e4
                "100000000,            10,         100,     100000",    // 1e8 * 10 / 1e4
                "500000,               1,          10,      50",
                "1000000000,           30,         100,     3000000",   // 1e9 * 30 / 1e4
                "50000000000,          25,         100,     125000000", // 5e10 * 25 / 1e4
                "10000000000000000,    25,         100,     25000000000000", // 1e16 * 25 / 1e4 (post-scale, overflow-safe)
                "1000000,              0,          10,      0"          // zero base fee -> zero
        })
        void swapFeeIsBaseFeeAtVaZero(long amountIn, int baseFeeBps, int binStep, long expectedFee) {
            long fee = FeeCalculator.calculateSwapFee(amountIn, baseFeeBps, 0, binStep);
            assertEquals(expectedFee, fee,
                    "VA=0 fee must be the pure base fee amountIn*baseFeeBps/10000");
            // Cross-check against the closed form to make the intent explicit.
            long byClosedForm = java.math.BigInteger.valueOf(amountIn)
                    .multiply(java.math.BigInteger.valueOf(baseFeeBps))
                    .divide(java.math.BigInteger.valueOf(10_000L))
                    .longValueExact();
            assertEquals(byClosedForm, fee, "VA=0 fee must equal amountIn*baseFeeBps/10000 exactly");
        }
    }

    // ── Conservation / bounds invariants over a broad grid (incl. VA>0) ───────

    @Nested
    @DisplayName("invariants: 0 <= fee <= amountIn and totalFeeBps <= MAX_FEE_BPS")
    class ConservationAndBoundsInvariants {

        @Test
        @DisplayName("fee is non-negative and never exceeds amountIn across the grid")
        void feeBounds() {
            long[] amounts = {0, 1, 7, 1_000, 1_000_000, 1_000_000_000L, 50_000_000_000L,
                    1_000_000_000_000L, 10_000_000_000_000_000L};
            int[] bases = {0, 1, 10, 25, 30, 50, 100, 1000, 5000};
            int[] vas = {0, 1, 10, 100, 500, 1000, 5000, 10_000};
            int[] steps = {1, 10, 20, 100, 500};
            for (long amountIn : amounts) {
                for (int base : bases) {
                    for (int va : vas) {
                        for (int step : steps) {
                            long fee = FeeCalculator.calculateSwapFee(amountIn, base, va, step);
                            assertTrue(fee >= 0,
                                    "fee >= 0 failed: amountIn=" + amountIn + " base=" + base
                                            + " va=" + va + " step=" + step + " fee=" + fee);
                            assertTrue(fee <= Math.max(0, amountIn),
                                    "fee <= amountIn failed: amountIn=" + amountIn + " base=" + base
                                            + " va=" + va + " step=" + step + " fee=" + fee);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("totalFeeBps is always within [0, MAX_FEE_BPS]")
        void totalFeeBpsBounds() {
            int[] bases = {0, 1, 10, 25, 30, 50, 100, 1000, 5000, 100_000};
            int[] vas = {0, 1, 10, 100, 500, 1000, 5000, 10_000};
            int[] steps = {1, 10, 20, 100, 500};
            for (int base : bases) {
                for (int va : vas) {
                    for (int step : steps) {
                        int total = FeeCalculator.totalFeeBps(base, va, step);
                        assertTrue(total >= 0,
                                "totalFeeBps >= 0 failed: base=" + base + " va=" + va + " step=" + step);
                        assertTrue(total <= FeeCalculator.MAX_FEE_BPS,
                                "totalFeeBps <= MAX_FEE_BPS failed: base=" + base + " va=" + va
                                        + " step=" + step + " total=" + total);
                    }
                }
            }
        }

        @Test
        @DisplayName("totalFeeBps is monotonic non-decreasing in VA (variable term only adds)")
        void monotonicInVa() {
            int base = 30, step = 100;
            int prev = FeeCalculator.totalFeeBps(base, 0, step);
            assertEquals(base, prev, "at VA=0 the surcharge is zero");
            for (int va = 1; va <= 10_000; va += 137) {
                int cur = FeeCalculator.totalFeeBps(base, va, step);
                assertTrue(cur >= prev,
                        "totalFeeBps must not decrease as VA rises: va=" + va
                                + " cur=" + cur + " prev=" + prev);
                assertTrue(cur >= base, "total must never drop below the base fee");
                prev = cur;
            }
        }
    }
}
