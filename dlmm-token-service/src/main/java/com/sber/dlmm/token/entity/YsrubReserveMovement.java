package com.sber.dlmm.token.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 9 #7.1 — append-only log of YSRUB mint/burn operations.
 *
 * <p>Each mint deposits SRUB into the reserve and credits the user with
 * YSRUB. Each burn does the reverse. The conversion ratio is 1:1 today
 * ({@code ratioMicro == 1_000_000}); future Sprint 10+ will move to a
 * yield-adjusted ratio that grows with accrued yield (this is why we
 * store the ratio per-movement — historical replay must use the ratio
 * in effect at the time, not "today's" value).
 *
 * <p>Idempotency: {@code idempotency_key} has a UNIQUE constraint; the
 * service's UPSERT semantics rely on this to make retry-safe mint/burn
 * calls. Clients pass any UUID; backend deduplicates.
 */
@Entity
@Table(name = "ysrub_reserve_movements")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class YsrubReserveMovement {

    public enum Direction { DEPOSIT, WITHDRAWAL }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Direction direction;

    @Column(name = "srub_amount", nullable = false)
    private long srubAmount;

    @Column(name = "ysrub_amount", nullable = false)
    private long ysrubAmount;

    /** Conversion ratio in micros (1.0 = 1_000_000). Sprint 9 ships at 1:1. */
    @Column(name = "ratio_micro", nullable = false)
    private long ratioMicro;

    @Column(name = "idempotency_key", length = 120, unique = true)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (ratioMicro == 0) ratioMicro = 1_000_000L;
    }
}
