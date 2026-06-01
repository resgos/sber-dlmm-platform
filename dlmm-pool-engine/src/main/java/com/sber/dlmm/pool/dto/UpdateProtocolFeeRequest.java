package com.sber.dlmm.pool.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Sprint 6 #3.1 — admin endpoint for tuning per-pool protocol fee share.
 *
 * <p>Range 0-5 (percent) is the legal-memo cap (Sprint 5 #5.G / 3.A
 * verdict: ≤5% stays within internal-clearing reg-frame; >5% would
 * require broker-dealer registration). The {@code @Max(5)} bound is
 * the hard safety — admin UI should add a softer warning above 3%.
 *
 * @param protocolFeePct protocol's share of collected fees, as a whole percent
 *                       (0–5); NOT scaled. 0 disables the protocol fee. Required
 */
public record UpdateProtocolFeeRequest(
        @NotNull @Min(0) @Max(5) Integer protocolFeePct
) {
}
