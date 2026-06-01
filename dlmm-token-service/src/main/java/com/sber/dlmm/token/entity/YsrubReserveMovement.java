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

    /**
     * Reserve flow direction. {@code DEPOSIT} = mint (SRUB enters the
     * reserve, YSRUB credited to the user); {@code WITHDRAWAL} = burn
     * (the reverse). Persisted as its {@code name()} string and matched
     * by the native-SQL reserve-total aggregate in the repository.
     */
    public enum Direction { DEPOSIT, WITHDRAWAL }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Direction direction;

    /** SRUB moved into/out of the reserve (raw ×10⁴ units). */
    @Column(name = "srub_amount", nullable = false)
    private long srubAmount;

    /** YSRUB credited/debited to the user (raw ×10⁴ units); equals
     *  {@link #srubAmount} at the 1:1 ratio shipped in Sprint 9. */
    @Column(name = "ysrub_amount", nullable = false)
    private long ysrubAmount;

    /** Conversion ratio in micros (1.0 = 1_000_000). Sprint 9 ships at 1:1. */
    @Column(name = "ratio_micro", nullable = false)
    private long ratioMicro;

    @Column(name = "idempotency_key", length = 120, unique = true)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * JPA lifecycle callback fired before INSERT: stamps {@link #createdAt}
     * and defaults {@link #ratioMicro} to {@code 1_000_000} (the 1:1
     * conversion) when the caller left it at its {@code 0} default, so a
     * movement is never persisted with a meaningless zero ratio.
     */
    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (ratioMicro == 0) ratioMicro = 1_000_000L;
    }
}
