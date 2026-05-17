package com.sber.dlmm.pool.dto;

/**
 * Sprint 4 #4.2 — admin endpoint for setting per-pool counterparty caps.
 *
 * Both fields nullable: omit a side to leave the existing cap untouched;
 * explicit {@code null} resets the cap (no limit). NULL semantics work
 * here because we pass through Hibernate which keeps NULL distinct from
 * "not provided".
 */
public record UpdateCounterpartyLimitsRequest(
        Long maxSingleSwapNominalX,
        Long maxSingleSwapNominalY
) {
}
