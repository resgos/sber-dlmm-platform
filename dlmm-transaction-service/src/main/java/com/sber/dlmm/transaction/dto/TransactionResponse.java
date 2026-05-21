package com.sber.dlmm.transaction.dto;

import com.sber.dlmm.common.enums.TransactionStatus;
import com.sber.dlmm.common.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record TransactionResponse(
    UUID id,
    TransactionType txType,
    TransactionStatus status,
    UUID userId,
    UUID poolId,
    UUID tokenInId,
    Long amountIn,
    UUID tokenOutId,
    Long amountOut,
    long feeAmount,
    BigDecimal feeRate,
    int binsCrossed,
    String idempotencyKey,
    String metadata,
    String errorMessage,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime confirmedAt,
    // Sprint 9-DS-r4 (P2-12) — admin "Mark reviewed" state. Null
    // when the row hasn't been reviewed yet; populated when an
    // admin POSTs /transactions/{id}/review. Surfaces to admin-bff
    // so its suspicious-detection can skip reviewed rows.
    LocalDateTime reviewedAt,
    UUID reviewedBy
) {}
