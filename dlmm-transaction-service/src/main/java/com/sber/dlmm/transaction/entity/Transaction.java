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

@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    private TransactionType txType;

    @Enumerated(EnumType.STRING)
    private TransactionStatus status;

    private UUID userId;

    private UUID poolId;

    private UUID tokenInId;

    private Long amountIn;

    private UUID tokenOutId;

    private Long amountOut;

    private long feeAmount;

    private BigDecimal feeRate;

    private int binsCrossed;

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

    @Column(columnDefinition = "text")
    private String metadata;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

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

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
