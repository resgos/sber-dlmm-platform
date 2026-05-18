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

    public enum OpType { MINT, CONVERT }

    public enum Status { COMPLETED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "op_type", nullable = false, length = 20)
    private OpType opType;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private long points;

    /** SRUB credited (CONVERT only); null for MINT. */
    @Column(name = "rub_amount")
    private Long rubAmount;

    @Column(nullable = false, unique = true, length = 128)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = Status.COMPLETED;
    }
}
