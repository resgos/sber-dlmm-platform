package com.sber.dlmm.pool.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Sprint 17 — accrued LP-farming reward for a single LP position. One row per
 * position (unique {@code position_id}); the accrual job UPSERTs it each cycle,
 * adding the position's share of the pool's pro-rated emission to
 * {@code unclaimedReward}. A claim sums {@code unclaimedReward} across the
 * user's rows, zeroes them, moves the total to {@code claimedReward}, and
 * credits the user's reward-token balance.
 *
 * <p>{@code unclaimedReward} / {@code claimedReward} are raw platform amounts
 * (uniform ×10⁴ scale) of the reward token (SSPAS).
 */
@Entity
@Table(name = "pool_position_rewards")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PoolPositionReward {

    @Id
    private UUID id;

    @Column(name = "position_id", nullable = false)
    private UUID positionId;

    @Column(name = "pool_id")
    private UUID poolId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "reward_token_id", nullable = false)
    private UUID rewardTokenId;

    /** Reward accrued but not yet claimed, raw units. */
    @Column(name = "unclaimed_reward", nullable = false)
    private long unclaimedReward;

    /** Reward already claimed (credited to the user's balance), raw units. */
    @Column(name = "claimed_reward", nullable = false)
    private long claimedReward;

    @Column(name = "last_accrual_at")
    private LocalDateTime lastAccrualAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
