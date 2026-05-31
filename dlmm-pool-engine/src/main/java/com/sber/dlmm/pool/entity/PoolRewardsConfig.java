package com.sber.dlmm.pool.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 17 — per-pool LP-farming reward configuration. Reward token is
 * Spasibo (SSPAS). One row per pool (unique {@code pool_id}) sets the daily
 * emission; the {@code LpFarmingScheduler} pro-rates it to each accrual cycle
 * and splits it across the pool's in-range active positions.
 *
 * <p>{@code emissionPerDay} is a raw platform amount (uniform ×10⁴ scale, see
 * scale.ts) of the reward token.
 */
@Entity
@Table(name = "pool_rewards_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PoolRewardsConfig {

    @Id
    private UUID id;

    @Column(name = "pool_id", nullable = false, unique = true)
    private UUID poolId;

    @Column(name = "reward_token_id", nullable = false)
    private UUID rewardTokenId;

    /** Reward emitted per day across all in-range liquidity, raw units. */
    @Column(name = "emission_per_day", nullable = false)
    private long emissionPerDay;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
