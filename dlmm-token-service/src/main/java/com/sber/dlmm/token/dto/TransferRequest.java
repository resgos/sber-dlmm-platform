package com.sber.dlmm.token.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TransferRequest(
        @NotNull UUID fromUserId,
        @NotNull UUID toUserId,
        @NotNull UUID tokenId,
        @Min(1) long amount,
        String idempotencyKey
) {
}
