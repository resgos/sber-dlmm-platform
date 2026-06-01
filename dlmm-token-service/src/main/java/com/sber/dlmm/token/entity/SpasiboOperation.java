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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 5 #5.3 + #5.4 — audit + idempotency anchor for SberSpasibo flows.
 *
 * <p>Two kinds of ops:
 * <ul>
 *   <li>{@link OpType#MINT} — SberSpasibo BU webhook credits SSPAS to a user
 *       (e.g. user earned points from a real-world transaction).</li>
 *   <li>{@link OpType#CONVERT} — user-initiated SSPAS → SRUB conversion at
 *       configured rate (default 1 SSPAS = 1 SRUB equivalent unit).</li>
 * </ul>
 *
 * <p>{@link #reference} is unique platform-wide and serves as idempotency
 * anchor. Replay of the same reference returns the existing row.
 */
@Entity
@Table(name = "spasibo_operations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpasiboOperation {

    /**
     * Kind of SberSpasibo operation this row records.
     * {@code MINT} = points credited from the Spasibo BU webhook;
     * {@code CONVERT} = user-initiated SSPAS → SRUB conversion.
     */
    public enum OpType { MINT, CONVERT }

    /**
     * Terminal outcome of the operation. {@code COMPLETED} on success;
     * {@code FAILED} when the flow errored (see {@link #errorMessage}).
     */
    public enum Status { COMPLETED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "op_type", nullable = false, length = 20)
    private OpType opType;

    /** User whose Spasibo/SRUB balance this operation moves. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** SberSpasibo points involved (integer point count, not a ×10⁴ amount). */
    @Column(nullable = false)
    private long points;

    /** SRUB credited (CONVERT only); null for MINT. */
    @Column(name = "rub_amount")
    private Long rubAmount;

    /** Platform-wide unique external reference; the idempotency anchor — a
     *  replay of the same reference returns the existing row. */
    @Column(nullable = false, unique = true, length = 128)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * JPA lifecycle callback fired before INSERT: defaults {@link #createdAt}
     * to now and {@link #status} to {@link Status#COMPLETED} when the caller
     * left them unset, so callers can persist a minimally-populated row.
     */
    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = Status.COMPLETED;
    }
}
