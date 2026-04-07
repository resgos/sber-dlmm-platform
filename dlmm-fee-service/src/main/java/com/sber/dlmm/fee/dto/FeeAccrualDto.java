package com.sber.dlmm.fee.dto;

import java.time.LocalDateTime;
import java.util.UUID;

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
