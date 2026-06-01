package com.sber.dlmm.transaction.dto;

import com.sber.dlmm.common.enums.B2BSettlementStatus;
import com.sber.dlmm.transaction.entity.B2BSettlement;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 4 #4.6 — outbound DTO for the B2B settlement API. Mirrors the
 * entity but only exposes fields the caller needs (e.g. {@code requestedBy}
 * is stripped — accountants don't need to know which operator ran it).
 *
 * <p>Monetary components ({@code amount} and the four fee-split fields)
 * are raw ×10⁴ integers.
 *
 * @param id             settlement id
 * @param fromUserId     corp account debited
 * @param toUserId       corp account credited
 * @param tokenId        token transferred
 * @param amount         principal transferred, raw ×10⁴ scale
 * @param reference      unique business reference (idempotency anchor)
 * @param status         lifecycle status (PENDING / COMPLETED / FAILED)
 * @param notes          optional free-text note
 * @param errorMessage   failure reason when {@code status} is FAILED
 * @param createdAt      insert timestamp
 * @param completedAt    when the settlement completed, or null
 * @param grossFeeAmount total fee charged (net + vat), raw ×10⁴ scale
 * @param vatAmount      НДС component of the fee, raw ×10⁴ scale
 * @param netFeeAmount   DLMM-revenue portion of the fee, raw ×10⁴ scale
 * @param vatRatePct     НДС rate applied, in whole percent
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
        LocalDateTime completedAt,
        // Sprint 5 #5.12 — fee + НДС split (Russian B2B "fee includes НДС"
        // convention). All four shown so accountant can reconcile against
        // 1С standalone fee entries and ФНС НДС-обязательство bookings.
        long grossFeeAmount,
        long vatAmount,
        long netFeeAmount,
        short vatRatePct
) {
    /**
     * Projects a persisted {@link B2BSettlement} entity into its outbound
     * DTO, copying every exposed field (the internal {@code requestedBy}
     * is intentionally dropped).
     *
     * @param s the settlement entity to convert
     * @return a response DTO mirroring the entity's public fields
     */
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
                s.getCompletedAt(),
                s.getGrossFeeAmount(),
                s.getVatAmount(),
                s.getNetFeeAmount(),
                s.getVatRatePct()
        );
    }
}
