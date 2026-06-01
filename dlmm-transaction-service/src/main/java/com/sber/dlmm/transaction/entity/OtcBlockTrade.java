package com.sber.dlmm.transaction.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 9 #6.1 (M#7) — OTC desk block trade.
 *
 * <p>State machine: REQUESTED → (QUOTED → ACCEPTED → SETTLED) | REJECTED | EXPIRED | CANCELLED.
 * Transitions are admin-driven (Sprint 9 MVP); Sprint 11 #F-11 RFQ
 * marketplace will let counterparties self-respond.
 *
 * <p>Settlement: when status moves to SETTLED, the underlying ledger
 * write happens via the existing swap or B2B-transfer rails, and
 * {@link #settlementTxId} points at the resulting {@code transactions}
 * row. This keeps the OTC layer purely a workflow record on top of
 * the existing transaction infrastructure.
 */
@Entity
@Table(name = "otc_block_trades")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OtcBlockTrade {

    /**
     * Workflow state of the block trade. Happy path is
     * REQUESTED → QUOTED → ACCEPTED → SETTLED; REJECTED, EXPIRED and
     * CANCELLED are terminal off-ramps.
     */
    public enum Status {
        /** Trade created, awaiting operator-provided quote. */
        REQUESTED,
        /** Quote attached; counterparty has until {@code quoteExpiresAt} to accept. */
        QUOTED,
        /** Counterparty accepted the quote; pending settlement. */
        ACCEPTED,
        /** Settlement executed; settlementTxId references the underlying ledger row. */
        SETTLED,
        /** Counterparty rejected the quote. Terminal. */
        REJECTED,
        /** Quote expired before counterparty action. Terminal. */
        EXPIRED,
        /** Admin operator cancelled. Terminal. */
        CANCELLED
    }

    /** Surrogate primary key (server-generated UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Corp account that requested the trade (the "maker" side). */
    @Column(name = "initiator_user_id", nullable = false)
    private UUID initiatorUserId;

    /** Corp account quoted to / settling against (the "taker" side). */
    @Column(name = "counterparty_user_id", nullable = false)
    private UUID counterpartyUserId;

    /** Admin operator who created this workflow record on the OTC desk. */
    @Column(name = "created_by_admin_id", nullable = false)
    private UUID createdByAdminId;

    /** Token the initiator gives up. */
    @Column(name = "token_in_id", nullable = false)
    private UUID tokenInId;

    /** Token the initiator receives. */
    @Column(name = "token_out_id", nullable = false)
    private UUID tokenOutId;

    /** Quantity offered in, raw ×10⁴ scale. */
    @Column(name = "amount_in", nullable = false)
    private long amountIn;

    /** Populated by the QUOTED transition. */
    @Column(name = "amount_out")
    private Long amountOut;

    /** Quote price in micros (token_out per 1 token_in × 10^6). */
    @Column(name = "quoted_price_micro")
    private Long quotedPriceMicro;

    /** When the operator attached the quote (QUOTED transition). */
    @Column(name = "quoted_at")
    private LocalDateTime quotedAt;

    /** Deadline by which the counterparty must accept; past it the quote may EXPIRE. */
    @Column(name = "quote_expires_at")
    private LocalDateTime quoteExpiresAt;

    /** Current workflow state; defaults to REQUESTED on insert. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    /** FK to the {@code transactions} ledger row created when this trade SETTLED. */
    @Column(name = "settlement_tx_id")
    private UUID settlementTxId;

    /** When the trade reached SETTLED. */
    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    /** Optional free-text operator notes. */
    @Column(length = 1000)
    private String notes;

    /** Insert timestamp; immutable, set by {@link #onCreate()}. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Last-modified timestamp, maintained by {@link #onCreate()}/{@link #onUpdate()}. */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * JPA pre-insert hook: defaults {@link #createdAt}/{@link #updatedAt}
     * to now (only when unset) and {@link #status} to REQUESTED.
     */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (status == null) status = Status.REQUESTED;
    }

    /**
     * JPA pre-update hook: refreshes {@link #updatedAt} to now on every
     * persisted mutation.
     */
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
