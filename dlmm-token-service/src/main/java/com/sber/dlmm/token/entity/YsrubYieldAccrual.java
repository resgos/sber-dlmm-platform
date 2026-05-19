package com.sber.dlmm.token.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 9 #7.2 — one row per (user, day) when the daily yield
 * scheduler credits yield. Idempotency = unique constraint on
 * (user_id, accrual_day) per Liquibase changeset 008.
 *
 * <p>{@code yieldAmount} is the integer YSRUB credited. Storing
 * {@code overnightRateBps} + {@code spreadBps} separately lets
 * compliance replay "what CBR rate did we use on day X".
 */
@Entity
@Table(name = "ysrub_yield_accruals")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class YsrubYieldAccrual {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Principal balance at the moment of accrual (smallest YSRUB unit). */
    @Column(name = "principal_at_accrual", nullable = false)
    private long principalAtAccrual;

    /** Yield credited this tick (smallest YSRUB unit). */
    @Column(name = "yield_amount", nullable = false)
    private long yieldAmount;

    @Column(name = "overnight_rate_bps", nullable = false)
    private int overnightRateBps;

    @Column(name = "spread_bps", nullable = false)
    private int spreadBps;

    @Column(name = "accrual_day", nullable = false)
    private LocalDate accrualDay;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
