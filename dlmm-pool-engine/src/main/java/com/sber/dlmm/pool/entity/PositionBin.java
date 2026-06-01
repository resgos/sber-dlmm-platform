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

import java.util.UUID;

/**
 * Join row linking an {@link LpPosition} to one {@link PoolBin}: how much of a
 * given bin's liquidity the position owns. Composite key (position_id, bin_id)
 * via {@link PositionBinId}. A position that spans N bins has N of these rows
 * (one per bin in {@code [binRangeMin, binRangeMax]}); together they break the
 * position's {@code totalLiquidityShares} down per bin.
 *
 * <p>{@link #liquidityShares} is a share of the bin's liquidity, not a token
 * amount — fees and withdrawals for the bin are split pro-rata on shares, and
 * the F-12 reconciliation seed scales these so no bin is over-owned (the sum of
 * a bin's position shares cannot exceed the bin's liquidity).
 *
 * <p>Lombok entity; no explicit methods.
 */
@Entity
@Table(name = "position_bins")
@IdClass(PositionBinId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PositionBin {

    /** Owning position (first half of the composite PK). */
    @Id
    @Column(name = "position_id", nullable = false)
    private UUID positionId;

    /** Bin within the pool that the position holds shares in (second half of the PK). */
    @Id
    @Column(name = "bin_id", nullable = false)
    private int binId;

    /** This position's share of the bin's liquidity (pro-rata basis for fees/withdrawals); not a token amount. */
    @Column(name = "liquidity_shares", nullable = false)
    private long liquidityShares;
}
