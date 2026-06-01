package com.sber.dlmm.token.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body to burn token supply from a specific user.
 *
 * <p>Sent to the admin-only {@code POST /tokens/burn} endpoint. Burning
 * deducts {@code amount} from the user's available balance and decreases
 * the token's total supply by the same amount; it fails if the user's
 * available balance is insufficient. The response is the user's updated
 * {@link BalanceResponse}.
 *
 * @param tokenId    token whose supply is being reduced (required)
 * @param fromUserId user whose balance is debited (required)
 * @param amount     quantity to burn, raw integer on the ×10⁴ platform scale (must be ≥ 1)
 */
public record BurnRequest(
        @NotNull UUID tokenId,
        @NotNull UUID fromUserId,
        @Min(1) long amount
) {
}
