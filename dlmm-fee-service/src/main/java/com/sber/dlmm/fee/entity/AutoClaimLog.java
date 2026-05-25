package com.sber.dlmm.fee.entity;

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
 * Sprint 12 G-16 — audit log of every auto-claim fire (success or
 * failure). Powers:
 *   - the per-position 1h cooldown (look up the most recent SUCCESS
 *     row for the position, skip if &lt; 1h ago).
 *   - the rolling-24h dailyCap (count SUCCESS rows for the user in
 *     the last 24h, compare to policy.dailyCap).
 *   - the /history endpoint the Profile drawer reads.
 *
 * <p>Failures are recorded so we don't keep retrying a broken position
 * every tick — but they don't count toward the cap, since no value
 * actually changed hands.
 */
@Entity
@Table(name = "auto_claim_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutoClaimLog {

    public enum Status { SUCCESS, FAILURE, SKIPPED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "position_id", nullable = false)
    private UUID positionId;

    @Column(name = "pool_id", nullable = false)
    private UUID poolId;

    @Builder.Default
    @Column(name = "amount_x", nullable = false)
    private long amountX = 0;

    @Builder.Default
    @Column(name = "amount_y", nullable = false)
    private long amountY = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "fired_at", nullable = false)
    private LocalDateTime firedAt;

    @PrePersist
    public void prePersist() {
        if (firedAt == null) {
            firedAt = LocalDateTime.now();
        }
    }
}
