package com.sber.dlmm.pool.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record SwapQuoteResponse(
        UUID poolId,
        UUID tokenInId,
        UUID tokenOutId,
        long amountIn,
        long estimatedAmountOut,
        long estimatedFee,
        int estimatedFeeBps,
        int estimatedBinsCrossed,
        BigDecimal estimatedPrice,
        BigDecimal priceImpactPct
) {
}
