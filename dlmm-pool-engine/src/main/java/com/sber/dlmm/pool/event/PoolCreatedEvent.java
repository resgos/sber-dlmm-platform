package com.sber.dlmm.pool.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record PoolCreatedEvent(
        UUID poolId,
        UUID tokenXId,
        UUID tokenYId,
        int binStep,
        LocalDateTime at
) {
}
