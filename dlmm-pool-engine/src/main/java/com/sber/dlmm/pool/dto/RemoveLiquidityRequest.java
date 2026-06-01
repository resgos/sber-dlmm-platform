package com.sber.dlmm.pool.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for withdrawing liquidity from an existing position
 * ({@code POST /api/v1/pools/remove-liquidity}).
 *
 * <p>Burns a fraction of the position's shares, returning the underlying tokens plus
 * any accrued fees. Returns a {@link RemoveLiquidityResponse}.
 *
 * @param positionId     id of the LP position to withdraw from
 * @param percentageBps  fraction of the position to remove, in basis points
 *                       (10000 = 100% / full exit, 5000 = half); NOT a token amount
 * @param idempotencyKey optional client key; a retry with the same key returns the
 *                       original result instead of withdrawing twice. May be {@code null}
 */
public record RemoveLiquidityRequest(
        @NotNull UUID positionId,
        int percentageBps,
        String idempotencyKey
) {
}
