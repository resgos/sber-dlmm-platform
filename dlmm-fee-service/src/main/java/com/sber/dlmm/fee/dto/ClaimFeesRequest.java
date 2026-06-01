package com.sber.dlmm.fee.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for claiming the unclaimed fees accrued on a single position.
 * The owning user is taken from the authenticated context (X-User-Id), not the
 * body, so a caller can only claim their own position's fees.
 *
 * @param positionId     position whose unclaimed fees to claim (required)
 * @param idempotencyKey optional caller-supplied key; when present and non-blank,
 *                       retries with the same key are de-duplicated so a fee is
 *                       credited at most once (safe to resend on timeout)
 */
public record ClaimFeesRequest(
        @NotNull UUID positionId,
        String idempotencyKey
) {}
