package com.sber.dlmm.pool.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SwapQuoteRequest(
        @NotNull UUID poolId,
        @NotNull UUID tokenInId,
        @Min(1) long amountIn
) {
}
