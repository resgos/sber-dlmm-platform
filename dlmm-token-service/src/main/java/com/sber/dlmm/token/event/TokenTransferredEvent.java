package com.sber.dlmm.token.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record TokenTransferredEvent(
        UUID tokenId,
        UUID from,
        UUID to,
        long amount,
        String idempotencyKey,
        LocalDateTime at
) {
}
