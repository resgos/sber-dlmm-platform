package com.sber.dlmm.pool.dto;

import java.util.List;

/**
 * Detailed single-pool view returned by {@code GET /api/v1/pools/{id}}.
 *
 * <p>Wraps the pool summary with its live bin liquidity distribution and the current
 * dynamic-fee state, for the pool detail page / order-book depth chart.
 *
 * @param pool                  the pool summary (identity, price, TVL, economics)
 * @param bins                  per-bin liquidity snapshot used to render the depth /
 *                              order-book view
 * @param volatilityAccumulator current value of the volatility accumulator that
 *                              drives the variable fee; unitless internal counter,
 *                              NOT scaled. Higher means recent price movement
 * @param currentDynamicFeeBps  the fee rate a swap would pay right now, in basis
 *                              points (base + variable component); NOT scaled
 * @param totalFeesCollectedX   gross lifetime fees accrued on the X side, raw integer
 *                              at 10⁻⁴ scale
 * @param totalFeesCollectedY   gross lifetime fees accrued on the Y side, raw integer
 *                              at 10⁻⁴ scale
 */
public record PoolDetailResponse(
        PoolResponse pool,
        List<BinResponse> bins,
        int volatilityAccumulator,
        int currentDynamicFeeBps,
        long totalFeesCollectedX,
        long totalFeesCollectedY
) {
}
