package com.sber.dlmm.token.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 9 #7.1 — published when a user burns YSRUB (withdraws SRUB
 * from the reserve).
 */
public record YsrubBurnedEvent(
        UUID movementId,
        UUID userId,
        long ysrubAmount,
        long srubAmount,
        long ratioMicro,
        long totalReserveSrubAfter,
        LocalDateTime burnedAt
) {}
