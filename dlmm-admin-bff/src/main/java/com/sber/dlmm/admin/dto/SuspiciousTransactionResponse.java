package com.sber.dlmm.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record SuspiciousTransactionResponse(
    UUID transactionId,
    UUID userId,
    UUID poolId,
    String reason,
    long amount,
    BigDecimal priceImpactPct,
    LocalDateTime timestamp
) {}
