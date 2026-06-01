package com.sber.dlmm.token.dto;

import com.sber.dlmm.token.entity.SpasiboOperation;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 5 #5.3 + #5.4 — outbound view of a {@code SpasiboOperation} row.
 *
 * <p>Returned by both Spasibo endpoints ({@code POST /spasibo/webhook} mint
 * and {@code POST /spasibo/convert}) so the caller sees the recorded
 * operation, including its terminal status and — for conversions — the SRUB
 * amount credited. Built from the entity via {@link #from(SpasiboOperation)}.
 *
 * @param id           unique operation id
 * @param opType       operation kind, {@code "MINT"} or {@code "CONVERT"} (enum name as string)
 * @param userId       user the operation belongs to
 * @param points       SSPAS points involved — a whole-point count, not a ×10⁴ scaled amount
 * @param rubAmount    SRUB credited for a CONVERT, raw ×10⁴; {@code null} for a MINT
 * @param reference    idempotency reference the operation was recorded under
 * @param status       terminal status, {@code "COMPLETED"} or {@code "FAILED"} (enum name as string)
 * @param errorMessage failure detail when {@code status} is FAILED; {@code null} otherwise
 * @param createdAt    timestamp the operation was recorded
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
