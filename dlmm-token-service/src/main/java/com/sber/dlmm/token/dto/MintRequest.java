package com.sber.dlmm.token.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record MintRequest(
        @NotNull UUID tokenId,
        @NotNull UUID toUserId,
        @Min(1) long amount
) {
}
