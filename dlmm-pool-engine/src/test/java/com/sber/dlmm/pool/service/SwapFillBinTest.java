package com.sber.dlmm.pool.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Meteora-exact per-bin swap math ({@link SwapService#fillBin}) shared by
 * quote() and swapTransactional(): a full bin crossing drains the WHOLE output
 * reserve with an EXCLUSIVE fee (added on top), while only the final partial bin
 * skims an INCLUSIVE fee from the remaining input.
 */
class SwapFillBinTest {

    @Test
    @DisplayName("full crossing drains the WHOLE output reserve with an exclusive fee")
    void fullCrossingDrainsWholeReserve() {
        // X→Y at price 1 (Y per X); bin holds 1,000,000 Y; remaining is huge → cross.
        SwapService.BinFill f = SwapService.fillBin(true, 0, 1_000_000, BigDecimal.ONE,
                10_000_000, 30, 0, 25);

        assertTrue(f.crossed(), "the bin is fully crossed");
        assertEquals(1_000_000, f.amountOut(), "drains the FULL bin reserve (not reserve×(1−fee))");
        assertEquals(1_000_000, f.netAmountIn(), "net input = ceil(reserveY/price)");
        // EXCLUSIVE fee added on top: net × feeBps / (10000 − feeBps), floored.
        assertEquals(1_000_000L * 30 / 9970, f.fee());
        assertEquals(f.netAmountIn() + f.fee(), f.consumedGross(), "gross = net + fee");
    }

    @Test
    @DisplayName("partial (final) bin: inclusive fee skimmed from the remaining input, floored output")
    void partialBinInclusiveFee() {
        // remaining 1000 ≪ gross-to-drain → partial fill, fee taken FROM the 1000.
        SwapService.BinFill f = SwapService.fillBin(true, 0, 1_000_000, BigDecimal.ONE,
                1_000, 30, 0, 25);

        assertFalse(f.crossed());
        assertEquals(1_000, f.consumedGross(), "consumes exactly the remaining input");
        assertEquals(1_000L * 30 / 10_000, f.fee(), "INCLUSIVE fee = remaining × feeBps/10000");
        assertEquals(1_000 - f.fee(), f.netAmountIn());
        assertEquals(f.netAmountIn(), f.amountOut(), "at price 1, out == net");
    }

    @Test
    @DisplayName("Y→X full crossing drains reserveX; net = ceil(reserveX × price)")
    void fullCrossingYtoX() {
        // Y→X at price 2 (Y per X); bin holds 500,000 X → 1,000,000 Y drains it.
        SwapService.BinFill f = SwapService.fillBin(false, 500_000, 0, new BigDecimal("2"),
                10_000_000, 30, 0, 25);

        assertTrue(f.crossed());
        assertEquals(500_000, f.amountOut(), "full reserveX out");
        assertEquals(1_000_000, f.netAmountIn(), "net Y = reserveX × price");
    }

    @Test
    @DisplayName("a bin with no output reserve is skipped (consumedGross 0)")
    void emptyOutputReserveSkipped() {
        SwapService.BinFill f = SwapService.fillBin(true, 5_000, 0, BigDecimal.ONE, 1_000, 30, 0, 25);
        assertEquals(0, f.consumedGross(), "skip — nothing to give");
    }
}
