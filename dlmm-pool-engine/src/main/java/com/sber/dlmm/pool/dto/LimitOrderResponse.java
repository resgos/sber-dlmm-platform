package com.sber.dlmm.pool.dto;

import com.sber.dlmm.pool.entity.LimitOrder;
import com.sber.dlmm.pool.entity.LimitOrderSide;
import com.sber.dlmm.pool.entity.LimitOrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Limit-order view. Token symbols are best-effort (null if token-service is
 * unreachable, same contract as the pool list). Amounts are raw (×10⁴) — the
 * user-ui divides by AMOUNT_SCALE at the boundary; limitPrice is not scaled.
 */
public record LimitOrderResponse(
        UUID id,
        UUID poolId,
        UUID tokenInId,
        UUID tokenOutId,
        String tokenInSymbol,
        String tokenOutSymbol,
        LimitOrderSide side,
        long amountIn,
        BigDecimal limitPrice,
        long amountOut,
        LimitOrderStatus status,
        LocalDateTime createdAt,
        LocalDateTime filledAt,
        LocalDateTime cancelledAt
) {
    public static LimitOrderResponse of(LimitOrder o, String tokenInSymbol, String tokenOutSymbol) {
        return new LimitOrderResponse(
                o.getId(), o.getPoolId(), o.getTokenInId(), o.getTokenOutId(),
                tokenInSymbol, tokenOutSymbol, o.getSide(), o.getAmountIn(),
                o.getLimitPrice(), o.getAmountOut(), o.getStatus(),
                o.getCreatedAt(), o.getFilledAt(), o.getCancelledAt());
    }
}
