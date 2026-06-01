package com.sber.dlmm.token.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 5 #5.7 — monthly invoice per B2B issuer.
 *
 * <p>Pricing components (units = SRUB-equivalent):
 * <ul>
 *   <li>{@code listingFee} — charged once, in the first invoice after
 *       KYB-APPROVED. Tier-dependent.</li>
 *   <li>{@code retainerFee} — monthly recurring. Tier-dependent.</li>
 *   <li>{@code volumeFee} — % bps of pool volume routed through the
 *       issuer's tokens during the period. Tier-dependent rate.</li>
 * </ul>
 *
 * <p>НДС split mirrors Sprint 5 #5.12 b2b_settlements: gross = net + vat.
 * Unique constraint on (issuer_id, period_start) prevents double-billing.
 */
@Entity
@Table(name = "b2b_invoices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class B2BInvoice {

    /**
     * Invoice lifecycle state: {@code ISSUED} (raised, unpaid) →
     * {@code PAID} (settled, see {@link #paidAt}) or {@code OVERDUE}
     * (past due, unpaid); {@code CANCELLED} = voided.
     */
    public enum Status { ISSUED, PAID, OVERDUE, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "issuer_id", nullable = false)
    private UUID issuerId;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    /** One-off listing fee, SRUB-equivalent units (charged only in the
     *  first invoice after KYB-APPROVED; 0 otherwise). */
    @Column(name = "listing_fee", nullable = false)
    private long listingFee;

    /** Monthly recurring retainer, SRUB-equivalent units (tier-dependent). */
    @Column(name = "retainer_fee", nullable = false)
    private long retainerFee;

    /** Volume-based fee for the period, SRUB-equivalent units. */
    @Column(name = "volume_fee", nullable = false)
    private long volumeFee;

    /** Gross amount due = {@link #netTotal} + {@link #vatAmount}
     *  (SRUB-equivalent units). */
    @Column(name = "gross_total", nullable = false)
    private long grossTotal;

    /** НДС (VAT) portion of the gross (SRUB-equivalent units). */
    @Column(name = "vat_amount", nullable = false)
    private long vatAmount;

    /** Net (pre-VAT) total = sum of the three fee components
     *  (SRUB-equivalent units). */
    @Column(name = "net_total", nullable = false)
    private long netTotal;

    /** VAT rate applied, whole percent (e.g. 20 for 20% НДС). */
    @Column(name = "vat_rate_pct", nullable = false)
    private short vatRatePct;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /**
     * JPA lifecycle callback fired before INSERT: stamps {@link #createdAt}
     * and defaults {@link #status} to {@link Status#ISSUED} when unset.
     */
    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = Status.ISSUED;
    }
}
