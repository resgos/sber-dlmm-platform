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
        LocalDateTime createdAt,
        // Gross lifetime fees (LP + protocol) accrued on each side. Exposed on
        // the list DTO so the admin-bff dashboard can aggregate platform-wide
        // "комиссия собрана" — previously only PoolDetailResponse carried these,
        // so the dashboard's sum read null → showed 0 ₽ (UI-test F-09).
        long totalFeesCollectedX,
        long totalFeesCollectedY
) {
}
