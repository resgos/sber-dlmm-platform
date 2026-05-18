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

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Corporate account debited (initiator's principal from JWT). */
    @Column(name = "from_user_id", nullable = false)
    private UUID fromUserId;

    /** Counterparty corporate account credited. */
    @Column(name = "to_user_id", nullable = false)
    private UUID toUserId;

    @Column(name = "token_id", nullable = false)
    private UUID tokenId;

    @Column(nullable = false)
    private long amount;

    /**
     * Caller-supplied unique business reference. Idempotency anchor:
     * resubmitting the same reference returns the existing settlement
     * without re-executing the deduct/credit.
     */
    @Column(nullable = false, unique = true, length = 128)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private B2BSettlementStatus status;

    @Column(length = 500)
    private String notes;

    /**
     * Operator who triggered the call (usually = fromUserId, but could
     * differ if a service-account / admin acted on behalf of a corp).
     */
    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * Sprint 5 #5.12 — fee + НДС split. Russian B2B fees follow "fee includes
     * НДС" convention. gross = total charged; vat = НДС component (must
     * remit to ФНС); net = DLMM-revenue side. See
     * {@code B2BSettlementService.computeFeeSplit()} for the formula.
     */
    @Column(name = "gross_fee_amount", nullable = false)
    private long grossFeeAmount;

    @Column(name = "vat_amount", nullable = false)
    private long vatAmount;

    @Column(name = "net_fee_amount", nullable = false)
    private long netFeeAmount;

    @Column(name = "vat_rate_pct", nullable = false)
    private short vatRatePct;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = B2BSettlementStatus.PENDING;
    }
}
