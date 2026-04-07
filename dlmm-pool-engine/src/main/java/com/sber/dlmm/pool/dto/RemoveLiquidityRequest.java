package com.sber.dlmm.pool.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record RemoveLiquidityRequest(
        @NotNull UUID positionId,
        int percentageBps,
        String idempotencyKey
) {
}
