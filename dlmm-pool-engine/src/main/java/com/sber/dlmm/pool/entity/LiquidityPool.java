package com.sber.dlmm.pool.entity;

import com.sber.dlmm.common.enums.PoolStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Root aggregate of the DLMM (Dynamic Liquidity Market Maker) domain: one
 * concentrated-liquidity trading pool for the ordered token pair
 * (token_x, token_y) at a fixed {@code binStep}.
 *
 * <p>A pool is the parent of many {@link PoolBin}s (the discrete price buckets
 * where liquidity lives) and many {@link LpPosition}s (per-user liquidity
 * ranges). The unique key (token_x_id, token_y_id, bin_step) means a given pair
 * can have several pools, one per bin-step "granularity" — a finer bin step
 * gives tighter price resolution but more bins to cross per swap.
 *
 * <h2>Price model</h2>
 * <p>Price is quoted as <b>token_y per 1 token_x</b>. {@link #basePrice} is the
 * price at {@link #activeBinId}; every other bin's price is
 * {@code basePrice·(1 + binStep/10000)^(binId − activeBinId)} (see
 * {@code BinMath.binPriceAtBin}). {@code activeBinId} tracks where the market
 * currently is and moves as swaps consume bins. Seed pools anchor the active
 * bin at {@code 2^23 = 8 388 608} (Trader Joe LB convention) so a u24 offset
 * can be negative without a sign column.
 *
 * <h2>Variable (dynamic) fee</h2>
 * <p>The swap fee is {@code baseFeeBps} plus a volatility surcharge driven by
 * {@link #volatilityAccumulator} (how far/fast the active bin has moved
 * recently), capped at {@link #maxVariableFeeBps} and decaying back to zero
 * over {@link #decayPeriodSeconds}. {@link #protocolFeePct} of every fee is
 * skimmed to the protocol treasury; the rest accrues to LPs.
 *
 * <h2>Amount scale (#14)</h2>
 * <p>All quantity fields ({@code totalTvlX/Y}, {@code volume24h}, the
 * {@code *FeesCollected*} / {@code *ProtocolFee*} accumulators, the
 * {@code maxSingleSwapNominal*} caps) are raw integer platform amounts where
 * 1 unit = 10⁻⁴ token (uniform ×10⁴ scale). Prices and bps fields are NEVER
 * scaled — they are dimensionless ratios.
 *
 * <h2>Invariants &amp; concurrency</h2>
 * <ul>
 *   <li>{@code totalTvlX/Y} are <em>cached rollups</em> incrementally
 *       maintained by the swap and add/remove paths; the nightly
 *       reconciliation job compares them against
 *       {@code PoolBinRepository.sumReservesByPool} and alerts on drift.</li>
 *   <li>{@link #version} provides JPA optimistic locking so two concurrent
 *       swaps on the same pool can't silently clobber each other's rollups —
 *       the loser retries (Sprint 4 #4.7).</li>
 * </ul>
 *
 * <p>Lombok-generated entity ({@code @Getter/@Setter/@Builder} +
 * all/no-args constructors); only the explicit {@link #prePersist()} callback
 * carries hand-written logic.
 */
@Entity
@Table(name = "liquidity_pools",
        uniqueConstraints = @UniqueConstraint(columnNames = {"token_x_id", "token_y_id", "bin_step"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LiquidityPool {

    @Id
    private UUID id;

    /** Base asset of the pair; price is quoted as token_y per 1 token_x. */
    @Column(name = "token_x_id", nullable = false)
    private UUID tokenXId;

    /** Quote asset of the pair (typically the stable SRUB / YSRUB side). */
    @Column(name = "token_y_id", nullable = false)
    private UUID tokenYId;

    /** Price granularity, in basis points: adjacent bins differ by 1+binStep/10000. */
    @Column(name = "bin_step", nullable = false)
    private int binStep;

    /** Floor swap fee (basis points), always charged regardless of volatility. */
    @Column(name = "base_fee_bps", nullable = false)
    private int baseFeeBps;

    /** Upper bound (basis points) on the volatility surcharge added to baseFeeBps. */
    @Column(name = "max_variable_fee_bps", nullable = false)
    private int maxVariableFeeBps;

    /**
     * Volatility state driving the variable-fee surcharge: grows as the active
     * bin jumps and decays over {@link #decayPeriodSeconds}. Dimensionless, not
     * an amount — never rescaled.
     */
    @Column(name = "volatility_accumulator", nullable = false)
    private int volatilityAccumulator;

    /** Seconds over which {@link #volatilityAccumulator} decays back to zero. */
    @Column(name = "decay_period_seconds", nullable = false)
    private int decayPeriodSeconds;

    /** Bin the market currently sits in; its price equals {@link #basePrice}. Moves as swaps cross bins. */
    @Column(name = "active_bin_id", nullable = false)
    private int activeBinId;

    /** Price (token_y per 1 token_x) at {@link #activeBinId}; anchor for every bin's derived price. */
    @Column(name = "base_price", nullable = false, precision = 36, scale = 18)
    private BigDecimal basePrice;

    /** Percent (0-100) of each fee skimmed to the protocol treasury; remainder accrues to LPs. */
    @Column(name = "protocol_fee_pct", nullable = false)
    private int protocolFeePct;

    /** Cached rollup of total token_x reserves across all bins, raw units. Reconciled nightly vs the bin sum. */
    @Column(name = "total_tvl_x", nullable = false)
    private long totalTvlX;

    /** Cached rollup of total token_y reserves across all bins, raw units. Reconciled nightly vs the bin sum. */
    @Column(name = "total_tvl_y", nullable = false)
    private long totalTvlY;

    /** Trailing-24h swap volume, raw units; aged by a scheduler so it drifts to 0 if swaps stop. */
    @Column(name = "volume_24h", nullable = false)
    private long volume24h;

    /** Lifetime fees taken on the X-side (LP + protocol combined pre-Sprint-6 #3.2), raw units. */
    @Column(name = "total_fees_collected_x", nullable = false)
    private long totalFeesCollectedX;

    /** Lifetime fees taken on the Y-side (LP + protocol combined pre-Sprint-6 #3.2), raw units. */
    @Column(name = "total_fees_collected_y", nullable = false)
    private long totalFeesCollectedY;

    /**
     * Sprint 6 #3.2 — protocol-side fee accumulator (separate from
     * {@code totalFeesCollectedX/Y} which conflated LP + protocol pre-#3.2).
     * Populated each swap with {@code fee × protocolFeePct / 100}.
     * Protocol treasury sweep job (Sprint 7+) drains this into the
     * treasury account.
     */
    @Column(name = "total_protocol_fee_x", nullable = false)
    private long totalProtocolFeeX;

    @Column(name = "total_protocol_fee_y", nullable = false)
    private long totalProtocolFeeY;

    /**
     * Sprint 4 #4.2 — per-pool counterparty limits.
     * Maximum amount_in for a single swap on the X-side (NULL = no cap).
     * Admin sets per pool based on liquidity depth + risk appetite.
     * Open-position aggregation across multiple swaps is Sprint 5+ work.
     */
    @Column(name = "max_single_swap_nominal_x")
    private Long maxSingleSwapNominalX;

    /** Same as above for Y-side. */
    @Column(name = "max_single_swap_nominal_y")
    private Long maxSingleSwapNominalY;

    /** Lifecycle gate: ACTIVE trades; PAUSED / EMERGENCY_SHUTDOWN / CLOSED block swaps and/or liquidity ops. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PoolStatus status;

    /** Admin user that created the pool (audit trail). */
    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * Sprint 4 #4.7 — optimistic locking. JPA increments on every
     * UPDATE; if two concurrent swaps both load version=N and try to
     * commit, the second gets OptimisticLockingFailureException.
     * SwapService catches and retries (bounded loop). See
     * docs/ANALYSIS-SAME-POOL-LOCK.md for the design rationale.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /**
     * JPA pre-insert hook supplying defaults for rows built without them:
     * a random {@link #id}, {@code now()} for {@link #createdAt}, status
     * {@code ACTIVE}, {@link #basePrice} of 1, and version 0. Keeps NOT-NULL
     * columns satisfied so callers can persist a minimally-populated builder.
     */
    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = PoolStatus.ACTIVE;
        if (basePrice == null) basePrice = BigDecimal.ONE;
        if (version == null) version = 0L;
    }
}
