package com.sber.dlmm.pool.dto;

import com.sber.dlmm.pool.entity.LimitOrder;
import com.sber.dlmm.pool.entity.LimitOrderSide;
import com.sber.dlmm.pool.entity.LimitOrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Limit-order view, returned by the limit-order endpoints (place / list / get).
 * Token symbols are best-effort (null if token-service is unreachable, same contract
 * as the pool list). Amounts are raw (×10⁴) — the user-ui divides by AMOUNT_SCALE at
 * the boundary; limitPrice is not scaled.
 *
 * @param id             order id
 * @param poolId         pool the order is on
 * @param tokenInId      id of the escrowed (input) token
 * @param tokenOutId     id of the token to be received on fill
 * @param tokenInSymbol  display symbol of the input token; best-effort, may be {@code null}
 * @param tokenOutSymbol display symbol of the output token; best-effort, may be {@code null}
 * @param side           order direction relative to token X ({@code BUY} / {@code SELL})
 * @param amountIn       escrowed input amount, raw integer at 10⁻⁴ scale
 * @param limitPrice     trigger price (token_y per token_x ratio), a real decimal —
 *                       NOT scaled
 * @param amountOut      output amount once filled, raw integer at 10⁻⁴ scale; 0 while
 *                       the order is still open
 * @param status         lifecycle state: OPEN, FILLED, or CANCELLED
 * @param createdAt      when the order was placed
 * @param filledAt       when the order filled, or {@code null} if not filled
 * @param cancelledAt    when the order was cancelled, or {@code null} if not cancelled
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
    /**
     * Build a response from a persisted order plus separately-resolved token symbols.
     *
     * @param o              the limit-order entity to project
     * @param tokenInSymbol  display symbol for the input token, or {@code null} if
     *                       unresolved
     * @param tokenOutSymbol display symbol for the output token, or {@code null} if
     *                       unresolved
     * @return a {@code LimitOrderResponse} mirroring the entity's fields
     */
    public static LimitOrderResponse of(LimitOrder o, String tokenInSymbol, String tokenOutSymbol) {
        return new LimitOrderResponse(
                o.getId(), o.getPoolId(), o.getTokenInId(), o.getTokenOutId(),
                tokenInSymbol, tokenOutSymbol, o.getSide(), o.getAmountIn(),
                o.getLimitPrice(), o.getAmountOut(), o.getStatus(),
                o.getCreatedAt(), o.getFilledAt(), o.getCancelledAt());
    }
}
