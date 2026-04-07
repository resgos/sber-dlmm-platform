package com.sber.dlmm.pool.dto;

import com.sber.dlmm.common.enums.PoolStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record PoolResponse(
        UUID id,
        UUID tokenXId,
        UUID tokenYId,
        String tokenXSymbol,
        String tokenYSymbol,
        int binStep,
        int baseFeeBps,
        int activeBinId,
        BigDecimal currentPrice,
        long totalTvlX,
        long totalTvlY,
        long volume24h,
        BigDecimal estimatedApy,
        PoolStatus status,
        LocalDateTime createdAt
) {
}
