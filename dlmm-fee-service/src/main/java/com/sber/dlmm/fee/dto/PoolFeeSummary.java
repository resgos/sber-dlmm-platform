package com.sber.dlmm.fee.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PoolFeeSummary(
        UUID poolId,
        String poolName,
        long unclaimedFeeX,
        long unclaimedFeeY,
        long totalEarnedFeeX,
        long totalEarnedFeeY,
        BigDecimal estimatedApyPct
) {}
