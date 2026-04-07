package com.sber.dlmm.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record TokenAnalyticsResponse(
    long totalSupply,
    long circulatingSupply,
    int holders,
    List<PriceHistoryEntry> priceHistory,
    long transferVolume24h
) {

    public record PriceHistoryEntry(
        LocalDateTime timestamp,
        BigDecimal price
    ) {}
}
