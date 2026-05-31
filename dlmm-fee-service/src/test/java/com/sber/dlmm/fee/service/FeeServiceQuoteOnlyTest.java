package com.sber.dlmm.fee.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Sprint 16 (Meteora parity) — pins the quote-only fee conversion math
 * (claimedY + floor(claimedX × price)).
 */
class FeeServiceQuoteOnlyTest {

    @Test
    void convertsXFeeToQuoteAndAddsExistingY() {
        // 100 raw X @ price 5 = 500 Y; plus 200 raw Y already accrued = 700.
        assertEquals(700L, FeeService.quoteOnlyTotalY(100, 200, new BigDecimal("5")));
    }

    @Test
    void floorsTheConversion() {
        // 3 X @ 2.5 = 7.5 → floor 7; plus 0 Y = 7.
        assertEquals(7L, FeeService.quoteOnlyTotalY(3, 0, new BigDecimal("2.5")));
    }

    @Test
    void noXFeeLeavesYUnchanged() {
        assertEquals(200L, FeeService.quoteOnlyTotalY(0, 200, new BigDecimal("5")));
    }

    @Test
    void nonPositiveOrNullPriceLeavesYUnchanged() {
        assertEquals(200L, FeeService.quoteOnlyTotalY(100, 200, BigDecimal.ZERO));
        assertEquals(200L, FeeService.quoteOnlyTotalY(100, 200, null));
    }

    @Test
    void overflowThrowsSoTheCallerCanFallBackToSplitCredit() {
        // claimedX × price beyond Long.MAX must throw — claimFees catches this and
        // falls back to the standard split credit rather than losing the claim.
        assertThrows(ArithmeticException.class,
                () -> FeeService.quoteOnlyTotalY(Long.MAX_VALUE / 2, 0, new BigDecimal("5000000")));
    }
}
