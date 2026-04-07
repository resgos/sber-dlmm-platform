package com.sber.dlmm.token.dto;

import com.sber.dlmm.common.enums.TokenType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateTokenRequest(
        @NotBlank String name,
        @NotBlank @Size(min = 2, max = 10) String symbol,
        @Min(0) @Max(18) int decimals,
        @Min(1) long initialSupply,
        long maxSupply,
        @NotNull TokenType tokenType,
        String underlyingAsset,
        String priceOracleId,
        boolean mintable,
        boolean burnable
) {
}
