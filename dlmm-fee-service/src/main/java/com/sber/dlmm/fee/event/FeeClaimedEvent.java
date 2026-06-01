package com.sber.dlmm.fee.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Immutable event published to the {@code fee-events} Kafka topic after a successful fee
 * claim, consumed downstream (e.g. notifications, analytics). Carries the settled amounts
 * and the pool's <i>real</i> X/Y token ids so consumers label the sides correctly rather
 * than guessing from map order.
 *
 * <p>For a quote-only claim the consolidated payout lands entirely in {@code claimedY}
 * (with {@code tokenYId} the quote token) and {@code claimedX} is {@code 0} — matching the
 * single-token credit the user actually received.
 *
 * @param positionId the position whose fees were claimed
 * @param userId     the user credited
 * @param poolId     the pool the position belongs to
 * @param claimedX   raw X-side amount credited (0 on a quote-only claim)
 * @param claimedY   raw Y-side amount credited (the full consolidated value on a quote-only claim)
 * @param tokenXId   the pool's base (X) token id, or {@code null} if it could not be resolved
 * @param tokenYId   the pool's quote (Y) token id, or {@code null} if it could not be resolved
 * @param claimedAt  the timestamp the claim was settled
 */
public record FeeClaimedEvent(
        UUID positionId,
        UUID userId,
        UUID poolId,
        long claimedX,
        long claimedY,
        UUID tokenXId,
        UUID tokenYId,
        LocalDateTime claimedAt
) {}
