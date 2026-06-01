package com.sber.dlmm.fee.dto;

import com.sber.dlmm.fee.entity.AutoClaimLog;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Read shape for /auto-claim-policy/history. Maps directly from
 * {@link AutoClaimLog} — no joins to position metadata, the frontend
 * formats positionId / poolId as it likes.
 *
 * @param id           auto-claim log row id
 * @param positionId   position the auto-claim targeted
 * @param poolId       pool the position belongs to
 * @param amountX      X-token amount claimed, raw ×10⁴ base units (0 for non-SUCCESS rows)
 * @param amountY      Y-token amount claimed, raw ×10⁴ base units (0 for non-SUCCESS rows)
 * @param status       outcome of the attempt (SUCCESS / FAILURE / SKIPPED)
 * @param errorMessage failure detail when {@code status == FAILURE}; otherwise {@code null}
 * @param firedAt      when the auto-claim fired
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
    /**
     * Maps an {@link AutoClaimLog} entity to its API DTO, copying every field
     * straight across.
     *
     * @param entity the persisted log row to convert
     * @return a DTO mirroring {@code entity}
     */
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
