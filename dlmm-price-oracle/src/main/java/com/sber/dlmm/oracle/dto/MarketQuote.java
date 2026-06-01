package com.sber.dlmm.oracle.dto;

import java.math.BigDecimal;

/**
 * A real-market quote pulled from a free external API: spot price in RUB plus
 * the 24h percentage change. Used by {@code MarketDataClient} → {@code PriceOracleService}.
 *
 * @param priceRub     spot price of the asset, in rubles (RUB)
 * @param change24hPct trailing-24h price change as a signed percentage (e.g. {@code -2.30} = -2.3%)
 */
public record MarketQuote(BigDecimal priceRub, BigDecimal change24hPct) {}
