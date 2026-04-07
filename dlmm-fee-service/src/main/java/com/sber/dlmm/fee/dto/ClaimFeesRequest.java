package com.sber.dlmm.fee.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ClaimFeesRequest(
        @NotNull UUID positionId,
        String idempotencyKey
) {}
