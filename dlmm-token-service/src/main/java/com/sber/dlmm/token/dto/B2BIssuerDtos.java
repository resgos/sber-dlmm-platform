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

    /** Non-instantiable holder for the B2B portal request/response records. */
    private B2BIssuerDtos() {}

    /**
     * Request body to register a corporate issuer for KYB review.
     *
     * <p>Sent to {@code POST /api/v1/b2b/issuers}. The new issuer starts in
     * PENDING status awaiting admin KYB approval/rejection. Registration is
     * idempotent by {@code inn}: re-posting an existing tax id returns the
     * existing issuer rather than creating a duplicate.
     *
     * @param inn          Russian tax id (ИНН): 10 digits for a legal entity, 12 for a sole proprietor (ИП); digits-only, checksum not validated (required)
     * @param legalName    full registered legal name (required, ≤ 255 chars)
     * @param displayName  short public-facing name shown in the UI (required, ≤ 128 chars)
     * @param contactEmail issuer contact email (required, valid email, ≤ 255 chars)
     * @param contactPhone issuer contact phone; optional (≤ 32 chars)
     * @param tier         requested pricing/service tier — see {@link B2BIssuer.Tier}; may be null to take the default
     */
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

    /**
     * Request body carrying the reason when an admin rejects an issuer's KYB.
     *
     * <p>Sent to {@code POST /api/v1/b2b/issuers/{id}/reject}. The reason is
     * persisted on the issuer for audit and surfaced back to the applicant.
     *
     * @param reason human-readable rejection reason (required, non-blank, ≤ 1000 chars)
     */
    public record RejectIssuerRequest(
            @NotBlank @Size(max = 1000) String reason
    ) {}

    /**
     * Response payload describing a corporate issuer and its KYB state.
     *
     * <p>Returned by the issuer endpoints (register, list, get, approve,
     * reject). Review fields ({@code reviewedBy}, {@code reviewedAt},
     * {@code rejectionReason}) are populated only once an admin has actioned
     * the KYB. Built from the entity via {@link #from(B2BIssuer)}.
     *
     * @param id              unique issuer id
     * @param inn             Russian tax id (ИНН) the issuer registered with
     * @param legalName       full registered legal name
     * @param displayName     short public-facing name
     * @param contactEmail    issuer contact email
     * @param contactPhone    issuer contact phone, or null when not provided
     * @param tier            pricing/service tier — see {@link B2BIssuer.Tier}
     * @param kybStatus       current KYB state (PENDING / APPROVED / REJECTED) — see {@link B2BIssuer.KybStatus}
     * @param reviewedBy      admin who approved/rejected, or null while still PENDING
     * @param reviewedAt      timestamp of the KYB decision, or null while still PENDING
     * @param rejectionReason reason captured on rejection, or null otherwise
     * @param createdAt       timestamp the issuer was registered
     */
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
        /**
         * Maps a {@link B2BIssuer} entity to its API response view.
         *
         * @param i the issuer entity to project
         * @return a response mirroring the entity's current fields
         */
        public static IssuerResponse from(B2BIssuer i) {
            return new IssuerResponse(i.getId(), i.getInn(), i.getLegalName(),
                    i.getDisplayName(), i.getContactEmail(), i.getContactPhone(),
                    i.getTier(), i.getKybStatus(), i.getReviewedBy(),
                    i.getReviewedAt(), i.getRejectionReason(), i.getCreatedAt());
        }
    }

    /**
     * Response payload describing one monthly invoice for a B2B issuer.
     *
     * <p>Returned by {@code GET /api/v1/b2b/issuers/{id}/invoices} and the
     * manual billing trigger. Monetary fields are SRUB-equivalent raw integer
     * amounts on the uniform ×10⁴ platform scale, and satisfy
     * {@code grossTotal = netTotal + vatAmount} (the gross is also the sum of
     * the three fee components). {@code vatRatePct} is an actual percentage
     * (e.g. {@code 20}), NOT a scaled amount. Built from the entity via
     * {@link #from(B2BInvoice)}.
     *
     * @param id          unique invoice id
     * @param issuerId    issuer the invoice is billed to
     * @param periodStart first day of the billed period (inclusive)
     * @param periodEnd   last day of the billed period (inclusive)
     * @param listingFee  one-off listing fee component, SRUB-equivalent raw ×10⁴
     * @param retainerFee monthly recurring retainer component, SRUB-equivalent raw ×10⁴
     * @param volumeFee   volume-based fee component for the period, SRUB-equivalent raw ×10⁴
     * @param grossTotal  total incl. VAT, SRUB-equivalent raw ×10⁴ ({@code netTotal + vatAmount})
     * @param vatAmount   VAT portion of the gross, SRUB-equivalent raw ×10⁴
     * @param netTotal    total excl. VAT, SRUB-equivalent raw ×10⁴
     * @param vatRatePct  VAT rate applied as a whole percentage (e.g. 20), NOT scaled
     * @param status      invoice lifecycle state — see {@link B2BInvoice.Status}
     * @param createdAt   timestamp the invoice was generated
     * @param paidAt      timestamp the invoice was paid, or null while unpaid
     */
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
        /**
         * Maps a {@link B2BInvoice} entity to its API response view.
         *
         * @param i the invoice entity to project
         * @return a response mirroring the entity's fields
         */
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
