package com.sber.dlmm.oracle.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * API response shape for a single asset's price feed — the serialised view
 * of a {@code PriceFeed} entity returned by the oracle's feed endpoints.
 *
 * <p>Prices are in rubles (SRUB) per unit of the asset;
 * {@code priceChange24hPct} is a signed percentage, not a fraction.
 *
 * @param id               feed identifier (UUID)
 * @param assetSymbol      asset ticker this feed tracks (e.g. {@code "USD"}, {@code "SBTC"})
 * @param source           origin of the quote (e.g. external provider or {@code "CBR"})
 * @param currentPrice     latest spot price, in rubles per unit
 * @param twapPrice        time-weighted average price over the oracle's TWAP window, in rubles per unit
 * @param priceChange24hPct trailing-24h price change as a signed percentage (e.g. {@code 1.50} = +1.5%)
 * @param updatedAtEpochMs last-refresh instant as Unix epoch milliseconds
 * @param updatedAt        last-refresh instant as a {@link LocalDateTime} (mirror of {@code updatedAtEpochMs})
 */
public record PriceFeedResponse(
        UUID id,
        String assetSymbol,
        String source,
        BigDecimal currentPrice,
        BigDecimal twapPrice,
        BigDecimal priceChange24hPct,
        long updatedAtEpochMs,
        LocalDateTime updatedAt
) {}
