package com.sber.dlmm.token.dto;

import com.sber.dlmm.common.enums.TokenType;

import java.time.LocalDateTime;
import java.util.UUID;

public record TokenResponse(
        UUID id,
        String name,
        String symbol,
        int decimals,
        long totalSupply,
        long maxSupply,
        TokenType tokenType,
        String underlyingAsset,
        boolean mintable,
        boolean burnable,
        boolean active,
        LocalDateTime createdAt
) {
}
