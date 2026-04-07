package com.sber.dlmm.token.event;

import com.sber.dlmm.common.enums.TokenType;

import java.time.LocalDateTime;
import java.util.UUID;

public record TokenCreatedEvent(
        UUID tokenId,
        String symbol,
        TokenType type,
        UUID createdBy,
        LocalDateTime at
) {
}
