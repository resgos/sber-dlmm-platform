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

@Entity
@Table(name = "pool_bins")
@IdClass(PoolBinId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PoolBin {

    @Id
    @Column(name = "pool_id", nullable = false)
    private UUID poolId;

    @Id
    @Column(name = "bin_id", nullable = false)
    private int binId;

    @Column(precision = 36, scale = 18)
    private BigDecimal price;

    @Column(nullable = false)
    private long liquidity;

    @Column(name = "reserve_x", nullable = false)
    private long reserveX;

    @Column(name = "reserve_y", nullable = false)
    private long reserveY;

    @Column(name = "composition_factor", precision = 36, scale = 18)
    private BigDecimal compositionFactor;

    @Column(name = "total_fee_x", nullable = false)
    private long totalFeeX;

    @Column(name = "total_fee_y", nullable = false)
    private long totalFeeY;

    @Column(name = "fee_growth_x", nullable = false)
    private long feeGrowthX;

    @Column(name = "fee_growth_y", nullable = false)
    private long feeGrowthY;
}
