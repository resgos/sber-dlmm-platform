package com.sber.dlmm.token.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record BurnRequest(
        @NotNull UUID tokenId,
        @NotNull UUID fromUserId,
        @Min(1) long amount
) {
}
