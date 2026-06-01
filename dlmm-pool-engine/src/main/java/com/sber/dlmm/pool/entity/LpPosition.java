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

/**
 * A single user's liquidity position in a {@link LiquidityPool}: the liquidity
 * they deposited across a contiguous bin range {@code [binRangeMin,
 * binRangeMax]} under a chosen {@link LiquidityStrategy} (shape of the deposit
 * across bins — SPOT / CURVE / BID_ASK). The per-bin breakdown lives in
 * {@link PositionBin} rows; this row is the aggregate + fee-accounting head.
 *
 * <h2>Fee accounting (per-unit-of-liquidity model)</h2>
 * <p>Fees are not pushed onto positions on every swap; instead each bin keeps a
 * monotonically increasing {@code feeGrowthX/Y} accumulator and this position
 * remembers the value it last settled at in {@link #lastFeeGrowthX} /
 * {@link #lastFeeGrowthY}. Owed fee since last settle is
 * {@code (bin.feeGrowth − lastFeeGrowth)·shares / BinMath.FEE_GROWTH_SCALE},
 * which is added into {@link #unclaimedFeeX} / {@link #unclaimedFeeY} until the
 * user claims (claim zeroes them and credits the balance).
 *
 * <h2>P&amp;L</h2>
 * <p>{@link #initialDepositX} / {@link #initialDepositY} are the cost basis
 * (reduced proportionally on partial removes) the UI compares against current
 * value to show position P&amp;L.
 *
 * <h2>Amount scale (#14)</h2>
 * <p>{@code unclaimedFee*} and {@code initialDeposit*} are raw integer platform
 * amounts (×10⁴). {@code totalLiquidityShares} is a share quantity (sum of the
 * {@link PositionBin#getLiquidityShares()} rows), and {@code lastFeeGrowth*} is
 * in the bin's 1e9 fixed-point space — neither is a token amount.
 *
 * <p>Lombok entity; only {@link #prePersist()} carries explicit logic.
 */
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

    /** Owner of the position (treasurer / LP). */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Pool this liquidity is deposited in. */
    @Column(name = "pool_id", nullable = false)
    private UUID poolId;

    /** Lowest bin id the position covers (inclusive). */
    @Column(name = "bin_range_min", nullable = false)
    private int binRangeMin;

    /** Highest bin id the position covers (inclusive). */
    @Column(name = "bin_range_max", nullable = false)
    private int binRangeMax;

    /** Distribution shape of the deposit across the range (SPOT uniform / CURVE / BID_ASK). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LiquidityStrategy strategy;

    /** Sum of this position's per-bin {@link PositionBin} shares; a share count, not a token amount. */
    @Column(name = "total_liquidity_shares", nullable = false)
    private long totalLiquidityShares;

    /** token_x fees accrued to the position but not yet claimed, raw units. */
    @Column(name = "unclaimed_fee_x", nullable = false)
    private long unclaimedFeeX;

    /** token_y fees accrued to the position but not yet claimed, raw units. */
    @Column(name = "unclaimed_fee_y", nullable = false)
    private long unclaimedFeeY;

    /** Bin X-fee-growth value at last settle; delta vs the bin drives newly-owed fee. 1e9 fixed-point. */
    @Column(name = "last_fee_growth_x", nullable = false)
    private long lastFeeGrowthX;

    /** Bin Y-fee-growth value at last settle; see {@link #lastFeeGrowthX}. 1e9 fixed-point. */
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

    /** Cost-basis on the Y-side (counterpart of {@link #initialDepositX}); raw units. */
    @Column(name = "initial_deposit_y", nullable = false)
    private long initialDepositY;

    /** False once fully removed/closed; inactive positions are excluded from scans and farming accrual. */
    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** When the position was closed (fully removed); null while still open. */
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /**
     * JPA pre-insert hook: assigns a random {@link #id} and stamps
     * {@link #createdAt} with {@code now()} when not already set, so a freshly
     * built position can be persisted without the caller filling those in.
     */
    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
