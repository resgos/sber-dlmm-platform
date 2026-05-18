package com.sber.dlmm.token.dto;

import com.sber.dlmm.token.entity.SpasiboOperation;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 5 #5.3 + #5.4 — outbound view of a SpasiboOperation row.
 */
public record SpasiboOperationResponse(
        UUID id,
        String opType,         // MINT or CONVERT
        UUID userId,
        long points,
        Long rubAmount,        // null for MINT
        String reference,
        String status,
        String errorMessage,
        LocalDateTime createdAt
) {
    public static SpasiboOperationResponse from(SpasiboOperation op) {
        return new SpasiboOperationResponse(
                op.getId(),
                op.getOpType().name(),
                op.getUserId(),
                op.getPoints(),
                op.getRubAmount(),
                op.getReference(),
                op.getStatus().name(),
                op.getErrorMessage(),
                op.getCreatedAt()
        );
    }
}
