package com.sber.dlmm.pool.dto;

import com.sber.dlmm.common.enums.LiquidityStrategy;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AddLiquidityRequest(
        @NotNull UUID poolId,
        @Min(1) long amountX,
        @Min(1) long amountY,
        int binRangeMin,
        int binRangeMax,
        @NotNull LiquidityStrategy strategy,
        String idempotencyKey
) {
}
