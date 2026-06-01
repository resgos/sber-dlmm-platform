package com.sber.dlmm.token.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 9 #7.1 — published when a user burns YSRUB (withdraws SRUB
 * from the reserve).
 *
 * <p>Mirror of {@link YsrubMintedEvent} for the withdrawal direction.
 * Consumed by notification-service (push to user), admin-bff (reserve
 * health dashboard), and the audit/compliance pipeline. Published via the
 * transactional outbox to the {@code token-events} Kafka topic (event type
 * {@code "YsrubBurned"}) once the burn commits.
 *
 * @param movementId            id of the {@code ysrub_reserve_movements} row this event records
 * @param userId                user who burned YSRUB and withdrew SRUB
 * @param ysrubAmount           YSRUB burned from the user, raw ×10⁴
 * @param srubAmount            SRUB returned from the reserve, raw ×10⁴
 * @param ratioMicro            conversion ratio applied, in micros (1.0 = 1_000_000); ships at 1:1 in Sprint 9 — NOT a ×10⁴ amount
 * @param totalReserveSrubAfter total SRUB held in the reserve after this burn, raw ×10⁴
 * @param burnedAt              moment the burn occurred
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
