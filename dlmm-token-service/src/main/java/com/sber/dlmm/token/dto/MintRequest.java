package com.sber.dlmm.token.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body to mint new token supply to a specific user.
 *
 * <p>Sent to the admin-only {@code POST /tokens/mint} endpoint. Minting
 * increases the token's total supply by {@code amount} and credits the
 * same amount to the target user's available balance. The response is the
 * recipient's updated {@link BalanceResponse}.
 *
 * @param tokenId  token whose supply is being increased (required)
 * @param toUserId user whose balance is credited (required)
 * @param amount   quantity to mint, raw integer on the ×10⁴ platform scale (must be ≥ 1)
 */
public record MintRequest(
        @NotNull UUID tokenId,
        @NotNull UUID toUserId,
        @Min(1) long amount
) {
}
