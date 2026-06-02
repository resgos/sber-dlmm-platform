package com.sber.dlmm.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link FeeCalculator#calculateSwapFeeExclusive} — the bin-crossing fee that
 * is added ON TOP of the net amount (Meteora's {@code compute_fee}), as opposed to
 * the inclusive {@link FeeCalculator#calculateSwapFee} skimmed out of a gross.
 */
class FeeCalculatorExclusiveFeeTest {

    @Test
    void exclusiveFeeIsAddedOnTopAndMatchesRateOfGross() {
        // net 1,000,000 at 30 bps, VA 0 → fee = net × 30 / (10000−30), floored.
        long fee = FeeCalculator.calculateSwapFeeExclusive(1_000_000, 30, 0, 25);
        assertEquals(1_000_000L * 30 / 9_970, fee);

        // The fee is ~30 bps of the GROSS (net + fee): the inclusive fee on that
        // gross must match the exclusive fee to within a rounding unit.
        long gross = 1_000_000 + fee;
        long inclusiveOnGross = FeeCalculator.calculateSwapFee(gross, 30, 0, 25);
        assertTrue(Math.abs(inclusiveOnGross - fee) <= 1,
                "exclusive fee on net ≈ inclusive fee on (net+fee)");
    }

    @Test
    void nonPositiveNetGivesZero() {
        assertEquals(0, FeeCalculator.calculateSwapFeeExclusive(0, 30, 0, 25));
        assertEquals(0, FeeCalculator.calculateSwapFeeExclusive(-7, 30, 0, 25));
    }

    @Test
    void zeroFeeRateGivesZero() {
        assertEquals(0, FeeCalculator.calculateSwapFeeExclusive(1_000_000, 0, 0, 25));
    }
}
