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
 *
 * @param currency        currency code being compared (e.g. {@code "USD"})
 * @param dlmmMarketRate  DLMM internal market price of the currency, in rubles (RUB)
 * @param cbrOfficialRate CBR official rate of the currency, in rubles (RUB)
 * @param spreadBps       signed DLMM-vs-CBR spread in basis points (100 bps = 1%); positive = DLMM above official, negative = below
 * @param dlmmUpdatedAt   timestamp of the DLMM market rate
 * @param cbrUpdatedAt    timestamp of the CBR official rate
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
