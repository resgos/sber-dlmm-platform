package com.sber.dlmm.fee.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * API view of one {@link com.sber.dlmm.fee.entity.FeeAccrual} ledger row — a
 * single token amount earned by a position, with its claim state. Used in the
 * fee-history listing.
 *
 * @param id         accrual row id
 * @param positionId position that earned the fee
 * @param poolId     pool the position belongs to
 * @param tokenId    token the fee is denominated in
 * @param amount     fee earned, raw ×10⁴ base units (1 = 10⁻⁴ token)
 * @param claimed    {@code true} once this accrual has been claimed
 * @param accruedAt  when the fee was accrued
 * @param claimedAt  when it was claimed; {@code null} while unclaimed
 */
public record FeeAccrualDto(
        UUID id,
        UUID positionId,
        UUID poolId,
        UUID tokenId,
        long amount,
        boolean claimed,
        LocalDateTime accruedAt,
        LocalDateTime claimedAt
) {}
