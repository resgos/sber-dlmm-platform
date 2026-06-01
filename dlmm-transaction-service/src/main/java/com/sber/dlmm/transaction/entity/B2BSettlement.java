package com.sber.dlmm.transaction.entity;

import com.sber.dlmm.common.enums.B2BSettlementStatus;
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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 4 #4.6 — audit record for a corp-to-corp token transfer (e.g.
 * Sber Treasury → MOEX clearing) routed through the DLMM token rails.
 *
 * <p>Idempotency is anchored on {@link #reference}: the corp ERP generates
 * a deterministic ref per business event, and a duplicate POST returns
 * the existing row instead of executing twice. Same-token transfer only
 * for prototype — FX-conversion settlement reuses the regular swap path
 * and lives in the {@code transactions} table.
 */
@Entity
@Table(name = "b2b_settlements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class B2BSettlement {

    /** Surrogate primary key (server-generated UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Corporate account debited (initiator's principal from JWT). */
    @Column(name = "from_user_id", nullable = false)
    private UUID fromUserId;

    /** Counterparty corporate account credited. */
    @Column(name = "to_user_id", nullable = false)
    private UUID toUserId;

    /** Token transferred (same on both legs — same-token transfer only for the prototype). */
    @Column(name = "token_id", nullable = false)
    private UUID tokenId;

    /** Principal transferred, raw ×10⁴ scale (excludes the fee). */
    @Column(nullable = false)
    private long amount;

    /**
     * Caller-supplied unique business reference. Idempotency anchor:
     * resubmitting the same reference returns the existing settlement
     * without re-executing the deduct/credit.
     */
    @Column(nullable = false, unique = true, length = 128)
    private String reference;

    /** Lifecycle state; defaults to PENDING on insert, then COMPLETED / FAILED. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private B2BSettlementStatus status;

    /** Optional free-text operator notes. */
    @Column(length = 500)
    private String notes;

    /**
     * Operator who triggered the call (usually = fromUserId, but could
     * differ if a service-account / admin acted on behalf of a corp).
     */
    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    /** Human-readable failure reason; populated when status becomes FAILED. */
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    /** Insert timestamp, defaulted by {@link #onCreate()}. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** When the settlement reached COMPLETED; null while PENDING/FAILED. */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * Sprint 5 #5.12 — fee + НДС split. Russian B2B fees follow "fee includes
     * НДС" convention. gross = total charged; vat = НДС component (must
     * remit to ФНС); net = DLMM-revenue side. See
     * {@code B2BSettlementService.computeFeeSplit()} for the formula.
     */
    /** Total fee charged (gross = net + vat), raw ×10⁴ scale. */
    @Column(name = "gross_fee_amount", nullable = false)
    private long grossFeeAmount;

    /** НДС component embedded in the gross fee (remitted to ФНС), raw ×10⁴ scale. */
    @Column(name = "vat_amount", nullable = false)
    private long vatAmount;

    /** DLMM-revenue portion of the fee (gross minus vat), raw ×10⁴ scale. */
    @Column(name = "net_fee_amount", nullable = false)
    private long netFeeAmount;

    /** НДС rate applied, in whole percent (e.g. 20). */
    @Column(name = "vat_rate_pct", nullable = false)
    private short vatRatePct;

    /**
     * JPA pre-insert hook: defaults {@link #createdAt} to now and
     * {@link #status} to PENDING when they are not already set.
     */
    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = B2BSettlementStatus.PENDING;
    }
}
