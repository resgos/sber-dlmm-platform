package com.sber.dlmm.pool.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record UpdateFeeParamsRequest(
        @Min(1) @Max(100) int baseFeeBps,
        @Min(0) @Max(1000) int maxVariableFeeBps,
        @Min(60) int decayPeriodSeconds
) {
}
