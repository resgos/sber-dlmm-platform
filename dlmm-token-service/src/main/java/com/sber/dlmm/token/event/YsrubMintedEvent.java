package com.sber.dlmm.token.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 9 #7.1 — published when a user mints YSRUB (deposits SRUB into
 * the money-market reserve).
 *
 * <p>Consumed by notification-service (push to user), admin-bff
 * (reserve health dashboard), and the audit/compliance pipeline.
 *
 * <p>Published via the transactional outbox to the {@code token-events}
 * Kafka topic (event type {@code "YsrubMinted"}) once the mint commits.
 *
 * @param movementId            id of the {@code ysrub_reserve_movements} row this event records
 * @param userId                user who deposited SRUB and received YSRUB
 * @param srubAmount            SRUB deposited into the reserve, raw ×10⁴
 * @param ysrubAmount           YSRUB credited to the user, raw ×10⁴
 * @param ratioMicro            conversion ratio applied, in micros (1.0 = 1_000_000); ships at 1:1 in Sprint 9 — NOT a ×10⁴ amount
 * @param totalReserveSrubAfter total SRUB held in the reserve after this mint, raw ×10⁴
 * @param mintedAt              moment the mint occurred
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
