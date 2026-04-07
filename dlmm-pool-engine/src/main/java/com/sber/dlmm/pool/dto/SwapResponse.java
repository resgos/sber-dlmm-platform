package com.sber.dlmm.pool.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record SwapResponse(
        UUID txId,
        UUID poolId,
        UUID tokenInId,
        UUID tokenOutId,
        long amountIn,
        long amountOut,
        long feeAmount,
        int feeBps,
        int binsCrossed,
        BigDecimal executionPrice,
        BigDecimal priceImpactPct
) {
}
