package com.sber.dlmm.oracle.dto;

import java.math.BigDecimal;

/**
 * API response for a time-weighted average price (TWAP) query on a single
 * asset over a trailing window.
 *
 * <p>The TWAP is computed from {@code PriceHistory} samples within the
 * window and is reported in rubles (SRUB) per unit of the asset.
 *
 * @param symbol        asset ticker the TWAP was computed for (e.g. {@code "USD"})
 * @param twapPrice     time-weighted average price over the window, in rubles per unit
 * @param periodMinutes length of the trailing averaging window, in minutes
 */
public record TwapResponse(
        String symbol,
        BigDecimal twapPrice,
        int periodMinutes
) {}
