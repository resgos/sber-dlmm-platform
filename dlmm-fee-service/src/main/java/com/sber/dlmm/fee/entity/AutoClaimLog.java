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

    /**
     * Outcome of one auto-claim attempt. {@code SUCCESS} = value was claimed
     * (counts toward the cooldown and dailyCap); {@code FAILURE} = the claim
     * threw (recorded to avoid retry-spamming a broken position, but not
     * counted); {@code SKIPPED} = below threshold / on cooldown / cap reached,
     * so nothing was attempted.
     */
    public enum Status { SUCCESS, FAILURE, SKIPPED }

    /** Surrogate primary key; DB-generated UUID, immutable once assigned. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** User whose auto-claim policy fired. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Position the auto-claim targeted. */
    @Column(name = "position_id", nullable = false)
    private UUID positionId;

    /** Pool the position belongs to. */
    @Column(name = "pool_id", nullable = false)
    private UUID poolId;

    /** X-token amount claimed (raw ×10⁴ base units); {@code 0} for non-SUCCESS rows. */
    @Builder.Default
    @Column(name = "amount_x", nullable = false)
    private long amountX = 0;

    /** Y-token amount claimed (raw ×10⁴ base units); {@code 0} for non-SUCCESS rows. */
    @Builder.Default
    @Column(name = "amount_y", nullable = false)
    private long amountY = 0;

    /** Outcome of the attempt; persisted as its enum name. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status;

    /** Failure detail when {@link Status#FAILURE}; {@code null} otherwise. */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** When the auto-claim fired; defaulted to now on persist if unset. */
    @Column(name = "fired_at", nullable = false)
    private LocalDateTime firedAt;

    /**
     * JPA lifecycle hook: stamps {@link #firedAt} with the current time when the
     * row is first persisted and no fire timestamp was supplied.
     */
    @PrePersist
    public void prePersist() {
        if (firedAt == null) {
            firedAt = LocalDateTime.now();
        }
    }
}
