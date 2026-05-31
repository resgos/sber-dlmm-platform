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

            // Re-enabled against the Stage 1 Meteora-shaped surcharge (was @Disabled
            // because the legacy VA²·binStep/1e10 term truncated to 0 here). Hand-derivation:
            //   variableFeeBps = floor( 50_000 * (5000*100)^2 / 1e11 )
            //                  = floor( 50_000 * (5e5)^2 / 1e11 )
            //                  = floor( 50_000 * 2.5e11 / 1e11 )
            //                  = floor( 1.25e16 / 1e11 ) = 125_000 -> capped to 1000
            //   totalFeeBps    = min(25 + 1000, 1000) = 1000
            //   totalFee       = floor( 5e10 * 1000 / 10_000 ) = 5_000_000_000
            //   baseFee        = floor( 5e10 *   25 / 10_000 ) =   125_000_000
            assertEquals(5_000_000_000L, totalFee, "volatile-market total fee is the capped 10% rate");
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

    // ── Sprint 16 math-audit fixes: overflow-safety, fee cap, decay clamp ──

    @Nested
    @DisplayName("Sprint 16: overflow-safety, MAX_FEE_BPS cap, decay clamp")
    class MathAuditFixTests {

        @Test
        @DisplayName("large post-scale amountIn (1e16) does not overflow long")
        void postScaleNoOverflow() {
            long amountIn = 10_000_000_000_000_000L; // 1e16 raw (1e12 tokens at the 1e-4 platform scale)
            long fee = FeeCalculator.calculateSwapFee(amountIn, 25, 0, 100);
            // 1e16 * 25 / 10_000 = 2.5e13. Pre-fix, amountIn*baseFeeBps overflowed.
            assertEquals(25_000_000_000_000L, fee, "1e16 at 25bps should be 2.5e13, not an overflowed value");
            assertTrue(fee > 0 && fee < amountIn, "fee stays positive and below input");
        }

        @Test
        @DisplayName("total fee rate is capped at MAX_FEE_BPS (10%)")
        void feeRateCapped() {
            assertEquals(FeeCalculator.MAX_FEE_BPS, FeeCalculator.totalFeeBps(5000, 0, 10),
                    "base 5000bps must cap at 1000bps (10%)");
            assertEquals(100_000_000L, FeeCalculator.calculateSwapFee(1_000_000_000L, 5000, 0, 10),
                    "fee on 1e9 at capped 10% is 1e8, not 5e8");
        }

        @Test
        @DisplayName("decay rate > 10000 is clamped (no negative-multiplier VA-zeroing bug)")
        void decayRateClampedHigh() {
            // decayPeriodSeconds=30 → scheduler computes decayRate=20000; without the
            // clamp this made (10000-20000) negative and slammed VA to 0 wrongly.
            assertEquals(0, FeeCalculator.decayVolatilityAccumulator(500, 20_000));
        }

        @Test
        @DisplayName("negative decay rate is clamped to 0 (VA unchanged)")
        void decayRateClampedLow() {
            assertEquals(500, FeeCalculator.decayVolatilityAccumulator(500, -100));
        }
    }

    // ── Stage 1 (#14): Meteora-shaped variable fee, exact goldens ──────────────

    @Nested
    @DisplayName("Stage 1: Meteora variable-fee shape control·(VA·binStep)²/1e11")
    class VariableFeeShapeTests {

        /**
         * Exact, hand-derived goldens for the NEW shape:
         *   variableFeeBps = floor( 50_000 * (VA*binStep)^2 / 1e11 )   (capped at 1000)
         *
         * Worked examples (control=50_000, denom=1e11):
         *   VA=0   , step=anything : numerator 0                                   -> 0   (THE invariant)
         *   VA=10  , step=100 : 50_000*(1_000)^2     =5.0e10 /1e11 = 0.5  -> floor 0
         *   VA=100 , step=20  : 50_000*(2_000)^2     =2.0e11 /1e11 = 2.0  ->       2
         *   VA=100 , step=100 : 50_000*(10_000)^2    =5.0e12 /1e11 = 50.0 ->      50
         *   VA=200 , step=100 : 50_000*(20_000)^2    =2.0e13 /1e11 = 200.0->     200
         *   VA=400 , step=100 : 50_000*(40_000)^2    =8.0e13 /1e11 = 800.0->     800
         *   VA=500 , step=50  : 50_000*(25_000)^2    =3.125e13/1e11 =312.5-> floor312
         *   VA=1000, step=20  : 50_000*(20_000)^2    =2.0e13 /1e11 = 200.0->     200
         *   VA=10000,step=100 : 50_000*(1_000_000)^2 =5.0e16 /1e11 =500_000-> cap1000
         */
        @ParameterizedTest(name = "VA={0}, binStep={1} -> variableFeeBps={2}")
        @CsvSource({
                "0, 1, 0",
                "0, 100, 0",
                "0, 500, 0",
                "10, 100, 0",
                "100, 20, 2",
                "100, 100, 50",
                "200, 100, 200",
                "400, 100, 800",
                "500, 50, 312",
                "1000, 20, 200",
                "10000, 100, 1000"
        })
        void variableFeeBpsExact(int va, int binStep, long expected) {
            assertEquals(expected, FeeCalculator.variableFeeBps(va, binStep),
                    "variableFeeBps mismatch for VA=" + va + ", binStep=" + binStep);
        }

        @Test
        @DisplayName("variable surcharge is EXACTLY 0 at VA=0 (the byte-identical invariant)")
        void zeroSurchargeAtVaZero() {
            for (int binStep : new int[]{1, 5, 10, 20, 50, 100, 250, 500}) {
                assertEquals(0L, FeeCalculator.variableFeeBps(0, binStep),
                        "surcharge must be 0 at VA=0 for binStep=" + binStep);
                // and the total collapses to base for a spread of base fees
                for (int base : new int[]{0, 1, 25, 30, 100}) {
                    assertEquals(base, FeeCalculator.totalFeeBps(base, 0, binStep),
                            "totalFeeBps must equal base at VA=0");
                }
            }
        }

        @Test
        @DisplayName("totalFeeBps adds the surcharge then caps at MAX_FEE_BPS")
        void totalAddsSurchargeThenCaps() {
            // VA=100, step=100 -> surcharge 50; base 30 -> 80
            assertEquals(80, FeeCalculator.totalFeeBps(30, 100, 100));
            // VA=400, step=100 -> surcharge 800; base 100 -> 900
            assertEquals(900, FeeCalculator.totalFeeBps(100, 400, 100));
            // VA=400, step=100 -> surcharge 800; base 300 -> 1100 -> capped 1000
            assertEquals(FeeCalculator.MAX_FEE_BPS, FeeCalculator.totalFeeBps(300, 400, 100));
        }

        @Test
        @DisplayName("no long-overflow for extreme VA·binStep (BigInteger intermediate)")
        void noOverflowExtreme() {
            // (VA*binStep)^2 here is ~ (2.1e9)^2 ≈ 4.4e18·... well past long if not BigInteger.
            long v = FeeCalculator.variableFeeBps(Integer.MAX_VALUE, 500);
            assertEquals(FeeCalculator.MAX_FEE_BPS, v, "extreme inputs saturate the cap, never overflow");
        }
    }
}
