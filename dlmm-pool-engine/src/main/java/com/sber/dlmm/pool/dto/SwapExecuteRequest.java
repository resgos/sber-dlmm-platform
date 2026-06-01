package com.sber.dlmm.pool.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/pools/swap/execute} — the
 * quote-execute side of the two-call swap flow (Batch G-02).
 *
 * <p>The {@code quoteId} is the UUID returned by the previous
 * {@code POST /swap/quote}. The {@code signature} is the user-bound
 * opaque token committed to the quote (currently JWT-subject hash).
 * If either is missing, validation fails before the service is called.
 *
 * @param quoteId   id of the quote to execute, as returned by {@code POST /swap/quote};
 *                  looks up the stored {@link QuotedSwap}
 * @param signature opaque user-bound token that must match the one committed to the
 *                  quote; mismatches are rejected. Must be non-blank
 */
public record SwapExecuteRequest(
        @NotNull UUID quoteId,
        @NotBlank String signature
) {
}
