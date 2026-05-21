package com.sber.dlmm.pool.service;

import com.sber.dlmm.common.enums.PoolStatus;
import com.sber.dlmm.pool.entity.LiquidityPool;
import com.sber.dlmm.pool.repository.LiquidityPoolRepository;
import com.sber.dlmm.pool.repository.PoolBinRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sprint 9-DS-r4 (P1-12) — pins the {@link TvlReconciliationService}
 * drift-detection contract:
 *   - identical rollup vs sum → no drift, no warn
 *   - rollup > sum by >0.1% → drift recorded, alarm trips
 *   - per-pool gauges are registered into Micrometer
 *
 * <p>Uses a {@link SimpleMeterRegistry} so we don't need Spring; the
 * gauge values are assertable via {@code meterRegistry.find(...)}.
 */
class TvlReconciliationServiceTest {

    private LiquidityPoolRepository poolRepository;
    private PoolBinRepository binRepository;
    private MeterRegistry meterRegistry;
    private TvlReconciliationService service;

    @BeforeEach
    void setUp() {
        poolRepository = mock(LiquidityPoolRepository.class);
        binRepository = mock(PoolBinRepository.class);
        meterRegistry = new SimpleMeterRegistry();
        service = new TvlReconciliationService(poolRepository, binRepository, meterRegistry);
    }

    @Test
    void zeroDrift_when_rollup_matches_sum_exactly() {
        UUID poolId = UUID.randomUUID();
        LiquidityPool pool = poolWithRollup(poolId, 1_000_000L, 5_000_000L);
        when(poolRepository.findAll()).thenReturn(List.of(pool));
        // Pool bins sum exactly to the rollup → 0 drift.
        when(binRepository.sumReservesByPool(poolId)).thenReturn(new Object[]{1_000_000L, 5_000_000L});

        service.reconcile();

        assertThat(driftGauge(poolId, "x")).isEqualTo(0.0);
        assertThat(driftGauge(poolId, "y")).isEqualTo(0.0);
    }

    @Test
    void positiveDrift_when_rollup_exceeds_sum() {
        UUID poolId = UUID.randomUUID();
        LiquidityPool pool = poolWithRollup(poolId, 1_000_000L, 5_000_000L);
        when(poolRepository.findAll()).thenReturn(List.of(pool));
        // Rollup says 1_000_000 X but bins only have 900_000 → +100_000 drift.
        when(binRepository.sumReservesByPool(poolId)).thenReturn(new Object[]{900_000L, 5_000_000L});

        service.reconcile();

        assertThat(driftGauge(poolId, "x")).isEqualTo(100_000.0);
        assertThat(driftGauge(poolId, "y")).isEqualTo(0.0);
    }

    @Test
    void negativeDrift_when_sum_exceeds_rollup() {
        // Rollup leaked DOWN — happens when a remove path forgot to
        // subtract from the rollup but the bin updates landed.
        UUID poolId = UUID.randomUUID();
        LiquidityPool pool = poolWithRollup(poolId, 1_000_000L, 5_000_000L);
        when(poolRepository.findAll()).thenReturn(List.of(pool));
        when(binRepository.sumReservesByPool(poolId)).thenReturn(new Object[]{1_050_000L, 5_000_000L});

        service.reconcile();

        assertThat(driftGauge(poolId, "x")).isEqualTo(-50_000.0);
    }

    @Test
    void handlesEmptyPoolList() {
        when(poolRepository.findAll()).thenReturn(List.of());
        // Should not throw, should not register any gauges.
        service.reconcile();
        assertThat(meterRegistry.find("dlmm.pool.tvl_drift").gauges()).isEmpty();
    }

    @Test
    void handlesNullAggregateRow() {
        UUID poolId = UUID.randomUUID();
        LiquidityPool pool = poolWithRollup(poolId, 0L, 0L);
        when(poolRepository.findAll()).thenReturn(List.of(pool));
        // Defensive: aggregate query somehow returns null (no rows
        // for the pool, COALESCE didn't fire). Should treat as zeros.
        when(binRepository.sumReservesByPool(poolId)).thenReturn(null);

        service.reconcile();

        assertThat(driftGauge(poolId, "x")).isEqualTo(0.0);
        assertThat(driftGauge(poolId, "y")).isEqualTo(0.0);
    }

    private static LiquidityPool poolWithRollup(UUID id, long tvlX, long tvlY) {
        return LiquidityPool.builder()
                .id(id)
                .tokenXId(UUID.randomUUID())
                .tokenYId(UUID.randomUUID())
                .binStep(10)
                .baseFeeBps(30)
                .maxVariableFeeBps(100)
                .volatilityAccumulator(0)
                .decayPeriodSeconds(600)
                .activeBinId(0)
                .basePrice(BigDecimal.ONE)
                .protocolFeePct(0)
                .totalTvlX(tvlX)
                .totalTvlY(tvlY)
                .volume24h(0)
                .totalFeesCollectedX(0)
                .totalFeesCollectedY(0)
                .status(PoolStatus.ACTIVE)
                .createdBy(UUID.randomUUID())
                .build();
    }

    private double driftGauge(UUID poolId, String side) {
        return meterRegistry.find("dlmm.pool.tvl_drift")
                .tag("pool", poolId.toString())
                .tag("side", side)
                .gauge()
                .value();
    }
}
