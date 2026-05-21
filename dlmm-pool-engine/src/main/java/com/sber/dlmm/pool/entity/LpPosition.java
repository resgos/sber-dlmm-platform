package com.sber.dlmm.pool.entity;

import com.sber.dlmm.common.enums.LiquidityStrategy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

@Entity
@Table(name = "lp_positions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LpPosition {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "pool_id", nullable = false)
    private UUID poolId;

    @Column(name = "bin_range_min", nullable = false)
    private int binRangeMin;

    @Column(name = "bin_range_max", nullable = false)
    private int binRangeMax;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LiquidityStrategy strategy;

    @Column(name = "total_liquidity_shares", nullable = false)
    private long totalLiquidityShares;

    @Column(name = "unclaimed_fee_x", nullable = false)
    private long unclaimedFeeX;

    @Column(name = "unclaimed_fee_y", nullable = false)
    private long unclaimedFeeY;

    @Column(name = "last_fee_growth_x", nullable = false)
    private long lastFeeGrowthX;

    @Column(name = "last_fee_growth_y", nullable = false)
    private long lastFeeGrowthY;

    /**
     * Sprint 9-DS-r4 (P1-10) — cost-basis for the position P&L
     * column. Sum of all X-side deposits to this position, reduced
     * proportionally on partial removes. Compared against
     * {@code currentValueX} to derive P&L. Persisted in base units
     * (matches {@code reserveX} convention). See Liquibase
     * changeset 011.
     */
    @Column(name = "initial_deposit_x", nullable = false)
    private long initialDepositX;

    @Column(name = "initial_deposit_y", nullable = false)
    private long initialDepositY;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
