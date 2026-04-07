package com.sber.dlmm.token.dto;

import java.util.UUID;

public record BalanceResponse(
        UUID userId,
        UUID tokenId,
        String symbol,
        long available,
        long locked,
        long total
) {
}
