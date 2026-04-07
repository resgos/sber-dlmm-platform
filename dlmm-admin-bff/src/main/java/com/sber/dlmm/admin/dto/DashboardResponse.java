package com.sber.dlmm.admin.dto;

import java.math.BigDecimal;

public record DashboardResponse(
    long totalUsers,
    long verifiedUsers,
    int totalPools,
    int activePools,
    BigDecimal totalTvlRub,
    BigDecimal volume24hRub,
    BigDecimal totalFeesCollectedRub,
    long activePositions,
    long transactionsToday
) {}
