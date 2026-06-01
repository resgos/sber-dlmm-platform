package com.sber.dlmm.pool.dto;

import com.sber.dlmm.pool.entity.LimitOrderSide;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Place a limit order ({@code POST /api/v1/pools/limit-orders}). {@code amountIn}
 * is in raw platform units (×10⁴); {@code limitPrice} is a real token_y-per-token_x
 * ratio (NOT scaled).
 *
 * <p>The input side is escrowed immediately and the order fills atomically when the
 * pool price crosses {@code limitPrice}. Returns a {@link LimitOrderResponse}.
 *
 * @param poolId         pool to place the order against
 * @param side           order direction relative to token X: {@code BUY} escrows Y
 *                       and fills when price ≤ limit; {@code SELL} escrows X and fills
 *                       when price ≥ limit
 * @param amountIn       amount of the escrowed (input) token, raw integer at 10⁻⁴
 *                       scale; must be ≥ 1
 * @param limitPrice     trigger price (token_y per token_x ratio), a real decimal —
 *                       NOT scaled; must be strictly positive
 * @param idempotencyKey optional client key; a retry with the same key returns the
 *                       original order instead of placing a duplicate. May be {@code null}
 */
public record CreateLimitOrderRequest(
        @NotNull UUID poolId,
        @NotNull LimitOrderSide side,
        @Min(1) long amountIn,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal limitPrice,
        String idempotencyKey
) {
}
