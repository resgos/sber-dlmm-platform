package com.sber.dlmm.pool.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One discrete price bucket ("bin") inside a {@link LiquidityPool} — the unit
 * where concentrated liquidity actually sits. Identified by the composite key
 * (pool_id, bin_id) via {@link PoolBinId}. A swap walks bins outward from the
 * pool's active bin, draining one token and filling the other until the
 * requested amount is satisfied.
 *
 * <p>Within a bin the price is effectively fixed at {@link #price} (token_y per
 * 1 token_x for {@code bin_id − activeBinId} steps off base); the bin holds a
 * mix of both tokens given by its reserves.
 *
 * <h2>F-12 bin invariant (critical)</h2>
 * <p>A bin's {@link #liquidity} is its value expressed in token_y:
 * <pre>liquidity = reserveX·price + reserveY</pre>
 * Any code or seed that (re)derives {@code liquidity} from reserves MUST agree
 * with this formula ({@code BinMath.binLiquidity}). Violating it makes an
 * isolated add→remove over- or under-return the quote token (the F-12 bug
 * family); the reconciliation seed
 * {@code docker/10-seed-reconcile-bin-invariant.sql} restores it.
 *
 * <h2>Amount scale (#14)</h2>
 * <p>{@code reserveX/Y}, {@code liquidity}, {@code totalFeeX/Y} are raw integer
 * platform amounts (×10⁴). {@link #price} and {@link #compositionFactor} are
 * ratios and are NEVER rescaled. The {@code feeGrowthX/Y} accumulators are in
 * the dedicated fixed-point space described below, not plain token units.
 *
 * <p>Lombok entity; no explicit methods — only field-level documentation.
 */
@Entity
@Table(name = "pool_bins")
@IdClass(PoolBinId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PoolBin {

    /** Owning pool (first half of the composite PK). */
    @Id
    @Column(name = "pool_id", nullable = false)
    private UUID poolId;

    /** Absolute LB-DLMM bin id (second half of the PK); offset from active sets the price. */
    @Id
    @Column(name = "bin_id", nullable = false)
    private int binId;

    /** Fixed price of this bin, token_y per 1 token_x. A ratio — not scaled. */
    @Column(precision = 36, scale = 18)
    private BigDecimal price;

    /** Bin value in token_y units: must equal {@code reserveX·price + reserveY} (F-12). Raw units. */
    @Column(nullable = false)
    private long liquidity;

    /** token_x held in this bin, raw units. */
    @Column(name = "reserve_x", nullable = false)
    private long reserveX;

    /** token_y held in this bin, raw units. */
    @Column(name = "reserve_y", nullable = false)
    private long reserveY;

    /** Cached {@code reserveY / liquidity} ratio (share of the bin held as quote token); not scaled. */
    @Column(name = "composition_factor", precision = 36, scale = 18)
    private BigDecimal compositionFactor;

    /** Lifetime token_x fees this bin has accrued, raw units (display/stats). */
    @Column(name = "total_fee_x", nullable = false)
    private long totalFeeX;

    /** Lifetime token_y fees this bin has accrued, raw units (display/stats). */
    @Column(name = "total_fee_y", nullable = false)
    private long totalFeeY;

    /**
     * Per-unit-of-liquidity X-fee accumulator in {@code BinMath.FEE_GROWTH_SCALE}
     * (1e9) fixed-point. A position's owed fee is
     * {@code (feeGrowthX − position.lastFeeGrowthX)·shares / SCALE}. The ×1e9
     * scale stops the {@code fee/liquidity} ratio flooring to integer 0
     * (Sprint 10 / #2). NOT a token amount.
     */
    @Column(name = "fee_growth_x", nullable = false)
    private long feeGrowthX;

    /** Per-unit-of-liquidity Y-fee accumulator; same fixed-point scale as {@link #feeGrowthX}. */
    @Column(name = "fee_growth_y", nullable = false)
    private long feeGrowthY;
}
