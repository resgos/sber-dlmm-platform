package com.sber.dlmm.pool.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record CreatePoolRequest(
        @NotNull UUID tokenXId,
        @NotNull UUID tokenYId,
        @Min(1) @Max(500) int binStep,
        @Min(1) @Max(100) int baseFeeBps,
        @NotNull BigDecimal initialPrice,
        int maxVariableFeeBps,
        int protocolFeePct,
        int decayPeriodSeconds
) {
    public CreatePoolRequest {
        if (maxVariableFeeBps <= 0) maxVariableFeeBps = 300;
        if (protocolFeePct <= 0) protocolFeePct = 20;
        if (decayPeriodSeconds <= 0) decayPeriodSeconds = 600;
    }
}
