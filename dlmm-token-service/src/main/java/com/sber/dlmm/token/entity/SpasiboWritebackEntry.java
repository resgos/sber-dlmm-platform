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
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 9-DS-r4 (P1-17) — one row per DLMM operation that earned
 * the user SberSpasibo cashback.
 *
 * <p>See {@code docs/SPASIBO-WRITEBACK-DESIGN.md} §5.3 for the
 * full design. MVP: SWAP-only accrual; HEDGE / ADD_LIQ / CLAIM_FEE
 * hooks land when their pool-engine events flow through the same
 * consumer (Sprint 10).
 *
 * <p>The {@code dlmm_tx_id} UNIQUE constraint is the idempotency
 * anchor — a re-delivered Kafka event for the same DLMM transaction
 * trips the constraint at the DB layer; the service catches and
 * treats as duplicate.
 */
@Entity
@Table(name = "spasibo_writeback_queue",
        uniqueConstraints = @UniqueConstraint(name = "uk_spasibo_wb_dlmm_tx",
                                              columnNames = "dlmm_tx_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpasiboWritebackEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Originating DLMM transaction id from the pool-engine event. */
    @Column(name = "dlmm_tx_id", nullable = false)
    private UUID dlmmTxId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Spasibo points to credit (integer; design caps daily/monthly). */
    @Column(name = "amount_points", nullable = false)
    private int amountPoints;

    @Column(name = "reason_code", nullable = false, length = 40)
    private String reasonCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "spasibo_tx_id", length = 64)
    private String spasiboTxId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = Status.PENDING;
    }

    /**
     * State machine: PENDING → ACCEPTED | REJECTED | RETRY → DEAD_LETTER.
     * <ul>
     *   <li>{@code PENDING} — created, not yet shipped to Spasibo BU</li>
     *   <li>{@code ACCEPTED} — Spasibo BU acknowledged credit</li>
     *   <li>{@code REJECTED} — terminal failure (user not found, etc.)</li>
     *   <li>{@code RETRY} — transient failure, scheduler retries with backoff</li>
     *   <li>{@code DEAD_LETTER} — exhausted retries; needs ops attention</li>
     * </ul>
     */
    public enum Status {
        PENDING, ACCEPTED, REJECTED, RETRY, DEAD_LETTER
    }

    /** Design §3 reason codes. */
    public static final class ReasonCodes {
        public static final String SWAP = "DLMM_SWAP";
        public static final String HEDGE = "DLMM_HEDGE";
        public static final String ADD_LIQUIDITY = "DLMM_ADD_LIQ";
        public static final String CLAIM_FEE = "DLMM_CLAIM_FEE";

        private ReasonCodes() {}
    }
}
