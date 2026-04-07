package com.sber.dlmm.token.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record TokenMintedEvent(
        UUID tokenId,
        UUID toUserId,
        long amount,
        long newTotalSupply,
        LocalDateTime at
) {
}
