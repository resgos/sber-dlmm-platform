package com.sber.dlmm.pool.dto;

/**
 * Sprint 4 #4.2 — admin endpoint for setting per-pool counterparty caps.
 *
 * Both fields nullable: omit a side to leave the existing cap untouched;
 * explicit {@code null} resets the cap (no limit). NULL semantics work
 * here because we pass through Hibernate which keeps NULL distinct from
 * "not provided".
 *
 * @param maxSingleSwapNominalX cap on the nominal size of a single swap on the X
 *                              side, raw integer at 10⁻⁴ scale; {@code null} = no
 *                              limit / leave unchanged (see class doc)
 * @param maxSingleSwapNominalY cap on the nominal size of a single swap on the Y
 *                              side, raw integer at 10⁻⁴ scale; {@code null} = no
 *                              limit / leave unchanged (see class doc)
 */
public record UpdateCounterpartyLimitsRequest(
        Long maxSingleSwapNominalX,
        Long maxSingleSwapNominalY
) {
}
