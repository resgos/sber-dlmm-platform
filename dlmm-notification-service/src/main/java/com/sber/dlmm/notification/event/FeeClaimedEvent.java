package com.sber.dlmm.notification.event;

import java.time.LocalDateTime;
import java.util.UUID;

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
