package com.sber.dlmm.pool.dto;

import java.util.UUID;

public record RemoveLiquidityResponse(
        UUID positionId,
        long withdrawnX,
        long withdrawnY,
        long claimedFeeX,
        long claimedFeeY
) {
}
