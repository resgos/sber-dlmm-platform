package com.sber.dlmm.transaction.dto;

import com.sber.dlmm.common.enums.B2BSettlementStatus;
import com.sber.dlmm.transaction.entity.B2BSettlement;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 4 #4.6 — outbound DTO for the B2B settlement API. Mirrors the
 * entity but only exposes fields the caller needs (e.g. {@code requestedBy}
 * is stripped — accountants don't need to know which operator ran it).
 */
public record B2BSettlementResponse(
        UUID id,
        UUID fromUserId,
        UUID toUserId,
        UUID tokenId,
        long amount,
        String reference,
        B2BSettlementStatus status,
        String notes,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime completedAt
) {
    public static B2BSettlementResponse from(B2BSettlement s) {
        return new B2BSettlementResponse(
                s.getId(),
                s.getFromUserId(),
                s.getToUserId(),
                s.getTokenId(),
                s.getAmount(),
                s.getReference(),
                s.getStatus(),
                s.getNotes(),
                s.getErrorMessage(),
                s.getCreatedAt(),
                s.getCompletedAt()
        );
    }
}
