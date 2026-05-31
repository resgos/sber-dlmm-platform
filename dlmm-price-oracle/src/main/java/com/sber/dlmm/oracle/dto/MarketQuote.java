package com.sber.dlmm.oracle.dto;

import java.math.BigDecimal;

/**
 * A real-market quote pulled from a free external API: spot price in RUB plus
 * the 24h percentage change. Used by {@code MarketDataClient} → {@code PriceOracleService}.
 */
public record MarketQuote(BigDecimal priceRub, BigDecimal change24hPct) {}
