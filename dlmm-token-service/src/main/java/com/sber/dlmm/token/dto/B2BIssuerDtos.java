package com.sber.dlmm.token.dto;

import com.sber.dlmm.token.entity.B2BInvoice;
import com.sber.dlmm.token.entity.B2BIssuer;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 5 #5.6 + #5.7 — DTOs for the B2B portal endpoints. Grouped
 * in one file since they're a tight set used by a single controller pair.
 */
public final class B2BIssuerDtos {

    private B2BIssuerDtos() {}

    public record RegisterIssuerRequest(
            // RU ИНН: 10 digits for legal entities, 12 for ИП. Pattern enforces
            // digits-only but doesn't validate the checksum — full ЕГРЮЛ
            // lookup is Sprint 7+ KYB integration.
            @NotBlank @Pattern(regexp = "\\d{10}|\\d{12}", message = "ИНН должен быть 10 или 12 цифр") String inn,
            @NotBlank @Size(max = 255) String legalName,
            @NotBlank @Size(max = 128) String displayName,
            @NotBlank @Email @Size(max = 255) String contactEmail,
            @Size(max = 32) String contactPhone,
            B2BIssuer.Tier tier
    ) {}

    public record RejectIssuerRequest(
            @NotBlank @Size(max = 1000) String reason
    ) {}

    public record IssuerResponse(
            UUID id,
            String inn,
            String legalName,
            String displayName,
            String contactEmail,
            String contactPhone,
            B2BIssuer.Tier tier,
            B2BIssuer.KybStatus kybStatus,
            UUID reviewedBy,
            LocalDateTime reviewedAt,
            String rejectionReason,
            LocalDateTime createdAt
    ) {
        public static IssuerResponse from(B2BIssuer i) {
            return new IssuerResponse(i.getId(), i.getInn(), i.getLegalName(),
                    i.getDisplayName(), i.getContactEmail(), i.getContactPhone(),
                    i.getTier(), i.getKybStatus(), i.getReviewedBy(),
                    i.getReviewedAt(), i.getRejectionReason(), i.getCreatedAt());
        }
    }

    public record InvoiceResponse(
            UUID id,
            UUID issuerId,
            LocalDate periodStart,
            LocalDate periodEnd,
            long listingFee,
            long retainerFee,
            long volumeFee,
            long grossTotal,
            long vatAmount,
            long netTotal,
            short vatRatePct,
            B2BInvoice.Status status,
            LocalDateTime createdAt,
            LocalDateTime paidAt
    ) {
        public static InvoiceResponse from(B2BInvoice i) {
            return new InvoiceResponse(i.getId(), i.getIssuerId(),
                    i.getPeriodStart(), i.getPeriodEnd(),
                    i.getListingFee(), i.getRetainerFee(), i.getVolumeFee(),
                    i.getGrossTotal(), i.getVatAmount(), i.getNetTotal(),
                    i.getVatRatePct(), i.getStatus(),
                    i.getCreatedAt(), i.getPaidAt());
        }
    }
}
