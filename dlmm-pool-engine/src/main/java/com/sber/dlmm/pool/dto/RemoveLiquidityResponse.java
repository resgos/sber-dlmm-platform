package com.sber.dlmm.pool.dto;

import java.util.UUID;

/**
 * Result of a remove-liquidity call
 * ({@code POST /api/v1/pools/remove-liquidity}).
 *
 * <p>Reports the principal returned to the user and the accrued fees claimed in the
 * same operation. All amounts are credited to the caller's balance.
 *
 * @param positionId  id of the position that was (partially or fully) withdrawn
 * @param withdrawnX  X-token principal returned, raw integer at 10⁻⁴ scale
 * @param withdrawnY  Y-token principal returned, raw integer at 10⁻⁴ scale
 * @param claimedFeeX X-token fees claimed alongside the withdrawal, raw integer at
 *                    10⁻⁴ scale
 * @param claimedFeeY Y-token fees claimed alongside the withdrawal, raw integer at
 *                    10⁻⁴ scale
 */
public record RemoveLiquidityResponse(
        UUID positionId,
        long withdrawnX,
        long withdrawnY,
        long claimedFeeX,
        long claimedFeeY
) {
}
