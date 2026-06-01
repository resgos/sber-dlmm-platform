package com.sber.dlmm.pool.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Admin request to retune a pool's dynamic-fee parameters
 * ({@code PUT /api/v1/pools/{id}/fee-params}).
 *
 * <p>Adjusts the base fee, the variable-fee ceiling, and how fast the volatility
 * component decays. All three are required.
 *
 * @param baseFeeBps         new base swap fee in basis points (1–100), the floor of
 *                           the dynamic fee; NOT scaled
 * @param maxVariableFeeBps  new cap on the volatility-driven variable fee component,
 *                           in basis points (0–1000); NOT scaled
 * @param decayPeriodSeconds new decay half-life in seconds (≥ 60) over which the
 *                           volatility accumulator relaxes toward the base fee
 */
public record UpdateFeeParamsRequest(
        @Min(1) @Max(100) int baseFeeBps,
        @Min(0) @Max(1000) int maxVariableFeeBps,
        @Min(60) int decayPeriodSeconds
) {
}
