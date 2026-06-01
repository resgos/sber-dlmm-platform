package com.sber.dlmm.transaction.entity;

import com.sber.dlmm.common.enums.TransactionStatus;
import com.sber.dlmm.common.enums.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Canonical ledger row for the transaction-service — one record per
 * user-facing money movement (SWAP, LP add/remove, fee claim, OTC
 * settlement, B2B settlement, etc.; see {@link TransactionType}).
 *
 * <p><b>Status lifecycle:</b> a row is typically created PENDING and
 * later flipped to CONFIRMED (stamping {@link #confirmedAt}) or FAILED
 * (stamping {@link #errorMessage}); see {@link TransactionStatus}.
 *
 * <p><b>Amount scale (#14):</b> {@link #amountIn}, {@link #amountOut}
 * and {@link #feeAmount} are raw integers where 1 unit = 10⁻⁴ token
 * (uniform 4 platform decimals). The backend never applies a token's
 * {@code decimals} column; UIs convert raw↔human at the API boundary.
 * {@link #feeRate} is a ratio (NOT scaled), so it stays meaningful
 * regardless of the quantity scaling.
 *
 * <p><b>Deduplication:</b> two independent unique keys guard against
 * double-writes — {@link #idempotencyKey} (client-supplied, any flow)
 * and {@link #poolEngineTxId} (pool-engine's swap row UUID, stamped
 * by the Kafka swap-event consumer). Either lets a re-delivered or
 * retried request resolve to the existing row instead of duplicating.
 *
 * <p>Timestamps are maintained by JPA callbacks: {@link #onCreate()}
 * sets {@link #createdAt}/{@link #updatedAt} on insert and
 * {@link #onUpdate()} refreshes {@link #updatedAt} on every update.
 */
@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    /** Surrogate primary key (server-generated UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** What kind of money movement this row represents (SWAP, LP add/remove, fee claim, …). */
    @Enumerated(EnumType.STRING)
    private TransactionType txType;

    /** Lifecycle state: PENDING → CONFIRMED / FAILED. */
    @Enumerated(EnumType.STRING)
    private TransactionStatus status;

    /** Owner of the transaction (the acting end user). */
    private UUID userId;

    /** Pool the movement touched; null for flows that aren't pool-scoped (e.g. B2B). */
    private UUID poolId;

    /** Token paid in / debited. */
    private UUID tokenInId;

    /** Amount paid in, raw ×10⁴ scale; null when not applicable. */
    private Long amountIn;

    /** Token received / credited. */
    private UUID tokenOutId;

    /** Amount received, raw ×10⁴ scale; null when not applicable. */
    private Long amountOut;

    /** Fee charged, raw ×10⁴ scale (quote-token units). */
    private long feeAmount;

    /** Effective fee rate applied (a ratio — NOT scaled). */
    private BigDecimal feeRate;

    /** Number of price bins the swap traversed (0 for non-swap rows). */
    private int binsCrossed;

    /** Client-supplied idempotency token; UNIQUE so a retried request resolves to the same row. */
    @Column(unique = true)
    private String idempotencyKey;

    /**
     * Sprint 9-DS-r4 (P0-4) — pool-engine's swap row UUID. Stamped by
     * {@code SwapEventConsumer} when persisting a row from a
     * {@code SwapExecuted} Kafka event. UNIQUE at the DB level so any
     * second consumer attempt (re-delivery, partition rebalance,
     * crash-resume) hits a constraint violation rather than silently
     * duplicating. Null for non-swap rows (LP add/remove, fee claim,
     * OTC, B2B settlement) — those flows don't originate from
     * pool-engine swap events.
     */
    @Column(name = "pool_engine_tx_id", unique = true)
    private UUID poolEngineTxId;

    /** Free-form JSON blob for flow-specific extra context (no fixed schema). */
    @Column(columnDefinition = "text")
    private String metadata;

    /** Human-readable failure reason; populated when status becomes FAILED. */
    private String errorMessage;

    /** Insert timestamp, set by {@link #onCreate()}. */
    private LocalDateTime createdAt;

    /** Last-modified timestamp, refreshed by {@link #onCreate()}/{@link #onUpdate()}. */
    private LocalDateTime updatedAt;

    /** When the row reached CONFIRMED; null while still PENDING/FAILED. */
    private LocalDateTime confirmedAt;

    /**
     * Sprint 9-DS-r4 (P2-12) — admin "Mark reviewed" flag for the
     * SuspiciousTransactionsPage. Null while the row is fresh; set
     * by {@code POST /api/v1/transactions/{id}/review} (admin only).
     * admin-bff's suspicious-detection skips reviewed rows so the
     * operator stops seeing acknowledged anomalies.
     */
    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /** Admin user id who marked the row reviewed; paired with {@link #reviewedAt}. */
    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    /**
     * JPA pre-insert hook: stamps both {@link #createdAt} and
     * {@link #updatedAt} with the current time.
     */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * JPA pre-update hook: refreshes {@link #updatedAt} to the current
     * time on every persisted mutation.
     */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
