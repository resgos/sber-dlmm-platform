package com.sber.dlmm.token.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 9 #7.1 — published when a user mints YSRUB (deposits SRUB into
 * the money-market reserve).
 *
 * <p>Consumed by notification-service (push to user), admin-bff
 * (reserve health dashboard), and the audit/compliance pipeline.
 */
public record YsrubMintedEvent(
        UUID movementId,
        UUID userId,
        long srubAmount,
        long ysrubAmount,
        long ratioMicro,
        long totalReserveSrubAfter,
        LocalDateTime mintedAt
) {}
