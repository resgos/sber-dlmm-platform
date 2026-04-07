package com.sber.dlmm.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PoolAnalyticsResponse(
    List<TvlHistoryEntry> tvlHistory,
    List<VolumeHistoryEntry> volumeHistory,
    List<FeeHistoryEntry> feeHistory,
    List<BinDistributionEntry> binDistribution,
    List<TopLpEntry> topLPs
) {

    public record TvlHistoryEntry(
        LocalDateTime timestamp,
        long tvlX,
        long tvlY
    ) {}

    public record VolumeHistoryEntry(
        LocalDateTime timestamp,
        long volume
    ) {}

    public record FeeHistoryEntry(
        LocalDateTime timestamp,
        long feeX,
        long feeY
    ) {}

    public record BinDistributionEntry(
        int binId,
        BigDecimal price,
        long liquidity,
        long reserveX,
        long reserveY
    ) {}

    public record TopLpEntry(
        UUID userId,
        String name,
        long totalLiquidity,
        long earnedFees
    ) {}
}
