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

    @Column(name = "token_x_id", nullable = false)
    private UUID tokenXId;

    @Column(name = "token_y_id", nullable = false)
    private UUID tokenYId;

    @Column(name = "bin_step", nullable = false)
    private int binStep;

    @Column(name = "base_fee_bps", nullable = false)
    private int baseFeeBps;

    @Column(name = "max_variable_fee_bps", nullable = false)
    private int maxVariableFeeBps;

    @Column(name = "volatility_accumulator", nullable = false)
    private int volatilityAccumulator;

    @Column(name = "decay_period_seconds", nullable = false)
    private int decayPeriodSeconds;

    @Column(name = "active_bin_id", nullable = false)
    private int activeBinId;

    @Column(name = "base_price", nullable = false, precision = 36, scale = 18)
    private BigDecimal basePrice;

    @Column(name = "protocol_fee_pct", nullable = false)
    private int protocolFeePct;

    @Column(name = "total_tvl_x", nullable = false)
    private long totalTvlX;

    @Column(name = "total_tvl_y", nullable = false)
    private long totalTvlY;

    @Column(name = "volume_24h", nullable = false)
    private long volume24h;

    @Column(name = "total_fees_collected_x", nullable = false)
    private long totalFeesCollectedX;

    @Column(name = "total_fees_collected_y", nullable = false)
    private long totalFeesCollectedY;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PoolStatus status;

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

    @PrePersist
    public void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = PoolStatus.ACTIVE;
        if (basePrice == null) basePrice = BigDecimal.ONE;
        if (version == null) version = 0L;
    }
}
