package com.sber.dlmm.oracle.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Sprint 5 #5.9 — DLMM market rate vs CBR official rate for a single
 * currency. Powers the admin-dashboard "spread vs official" tile.
 *
 * <p>{@code spreadBps} is signed: positive = DLMM market rate is ABOVE
 * CBR official (DLMM USD costs more rubles than the bank says), negative =
 * DLMM is cheaper than official. Treasury desk uses this to gauge
 * arbitrage / efficiency: a wide positive spread on a thin pool is a
 * liquidity-call signal.
 */
public record CbrSpreadResponse(
        String currency,           // e.g. "USD"
        BigDecimal dlmmMarketRate, // DLMM internal market price in RUB
        BigDecimal cbrOfficialRate, // CBR official rate in RUB
        BigDecimal spreadBps,       // signed, 100 bps = 1%
        LocalDateTime dlmmUpdatedAt,
        LocalDateTime cbrUpdatedAt
) {
}
