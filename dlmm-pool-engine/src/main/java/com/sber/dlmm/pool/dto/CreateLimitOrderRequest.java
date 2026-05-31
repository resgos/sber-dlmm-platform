package com.sber.dlmm.pool.dto;

import com.sber.dlmm.pool.entity.LimitOrderSide;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Place a limit order. {@code amountIn} is in raw platform units (×10⁴);
 * {@code limitPrice} is a real token_y-per-token_x ratio (NOT scaled).
 */
public record CreateLimitOrderRequest(
        @NotNull UUID poolId,
        @NotNull LimitOrderSide side,
        @Min(1) long amountIn,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal limitPrice,
        String idempotencyKey
) {
}
