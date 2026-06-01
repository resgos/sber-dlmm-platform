package com.sber.dlmm.oracle.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 9-DS-r4 (P1-11) — per-pool OHLCV candle.
 *
 * <p>Aggregation source: SwapExecuted Kafka events on {@code pool-events}.
 * Each swap contributes one tick at its execution price; the aggregator
 * (see {@code OhlcvAggregator}) maintains per-(pool, minute) buckets in
 * memory and writes completed buckets to this table every 30 seconds.
 *
 * <p>Storage choice: Postgres for MVP — matches the rest of the
 * platform's persistence story, no new infra dep. Migration to
 * ClickHouse planned for Sprint 10 once daily candle volume crosses
 * ~1M rows (today ≈15k/day across 22 seed pools at 1m granularity).
 *
 * <p>The {@code intervalSec} column is the bucket width (60 for 1-minute
 * candles); leaving room for a future hourly aggregation without a
 * second table.
 */
@Entity
@Table(name = "ohlcv_candles",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ohlcv_candles_pool_interval_time",
                columnNames = {"pool_id", "interval_sec", "open_time"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OhlcvCandle {

    /** Surrogate primary key (server-generated UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Pool this candle aggregates swaps for; part of the (pool, interval, openTime) unique key. */
    @Column(name = "pool_id", nullable = false)
    private UUID poolId;

    /** Bucket width in seconds. 60 = 1m candle. */
    @Column(name = "interval_sec", nullable = false)
    private int intervalSec;

    /** Start of the bucket, UTC. */
    @Column(name = "open_time", nullable = false)
    private LocalDateTime openTime;

    /** Open: execution price of the first swap in the bucket (quote-per-base ratio). */
    @Column(name = "open_price", nullable = false, precision = 30, scale = 18)
    private BigDecimal openPrice;

    /** High: maximum swap execution price observed in the bucket. */
    @Column(name = "high_price", nullable = false, precision = 30, scale = 18)
    private BigDecimal highPrice;

    /** Low: minimum swap execution price observed in the bucket. */
    @Column(name = "low_price", nullable = false, precision = 30, scale = 18)
    private BigDecimal lowPrice;

    /** Close: execution price of the last swap in the bucket (quote-per-base ratio). */
    @Column(name = "close_price", nullable = false, precision = 30, scale = 18)
    private BigDecimal closePrice;

    /** Sum of swap.amountIn in base units. Direction is implicit from the pool's tokenX/Y order. */
    @Column(name = "volume_in", nullable = false)
    private long volumeIn;

    /** Number of swaps aggregated into this bucket (the tick count behind the OHLC values). */
    @Column(name = "swap_count", nullable = false)
    private int swapCount;
}
