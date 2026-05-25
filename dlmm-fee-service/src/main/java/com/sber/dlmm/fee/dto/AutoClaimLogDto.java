package com.sber.dlmm.fee.dto;

import com.sber.dlmm.fee.entity.AutoClaimLog;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Read shape for /auto-claim-policy/history. Maps directly from
 * {@link AutoClaimLog} — no joins to position metadata, the frontend
 * formats positionId / poolId as it likes.
 */
public record AutoClaimLogDto(
        UUID id,
        UUID positionId,
        UUID poolId,
        long amountX,
        long amountY,
        AutoClaimLog.Status status,
        String errorMessage,
        LocalDateTime firedAt
) {
    public static AutoClaimLogDto from(AutoClaimLog entity) {
        return new AutoClaimLogDto(
                entity.getId(),
                entity.getPositionId(),
                entity.getPoolId(),
                entity.getAmountX(),
                entity.getAmountY(),
                entity.getStatus(),
                entity.getErrorMessage(),
                entity.getFiredAt()
        );
    }
}
