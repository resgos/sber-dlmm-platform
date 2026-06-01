package com.sber.dlmm.token.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body to transfer tokens from one user to another.
 *
 * <p>Sent to {@code POST /tokens/transfer} (open to any authenticated
 * caller). The transfer debits {@code fromUserId} and credits
 * {@code toUserId} atomically in a single transaction, failing if the
 * source has insufficient available balance. Supplying an
 * {@code idempotencyKey} makes the call retry-safe: a replay of the same
 * key is rejected as a duplicate (HTTP 409) rather than transferring twice.
 *
 * @param fromUserId     user whose balance is debited (required)
 * @param toUserId       user whose balance is credited (required)
 * @param tokenId        token being moved (required)
 * @param amount         quantity to transfer, raw integer on the ×10⁴ platform scale (must be ≥ 1)
 * @param idempotencyKey optional client-supplied dedup key; a recommended UUID. When omitted the transfer is not deduplicated
 */
public record TransferRequest(
        @NotNull UUID fromUserId,
        @NotNull UUID toUserId,
        @NotNull UUID tokenId,
        @Min(1) long amount,
        String idempotencyKey
) {
}
