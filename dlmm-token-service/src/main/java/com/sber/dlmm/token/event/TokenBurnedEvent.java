package com.sber.dlmm.token.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record TokenBurnedEvent(
        UUID tokenId,
        UUID fromUserId,
        long amount,
        long newTotalSupply,
        LocalDateTime at
) {
}
