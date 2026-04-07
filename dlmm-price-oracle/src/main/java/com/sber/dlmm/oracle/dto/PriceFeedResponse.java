package com.sber.dlmm.oracle.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

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
