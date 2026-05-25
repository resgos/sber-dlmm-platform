package com.sber.dlmm.pool.service;

import com.sber.dlmm.common.enums.LiquidityStrategy;
import com.sber.dlmm.common.enums.PoolStatus;
import com.sber.dlmm.common.exception.InvalidBinRangeException;
import com.sber.dlmm.common.exception.PoolNotActiveException;
import com.sber.dlmm.common.exception.PoolNotFoundException;
import com.sber.dlmm.common.outbox.OutboxService;
import com.sber.dlmm.pool.client.TokenServiceClient;
import com.sber.dlmm.pool.client.UserServiceClient;
import com.sber.dlmm.pool.dto.AddLiquidityRequest;
import com.sber.dlmm.pool.dto.PreviewAddLiquidityResponse;
import com.sber.dlmm.pool.entity.LiquidityPool;
import com.sber.dlmm.pool.repository.LiquidityPoolRepository;
import com.sber.dlmm.pool.repository.LpPositionRepository;
import com.sber.dlmm.pool.repository.PoolBinRepository;
import com.sber.dlmm.pool.repository.PositionBinRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 11 G-22 — pins the previewAddLiquidity contract.
 *
 * <p>Key invariants:
 * <ul>
 *   <li>Read-only — no balance deduction, no idempotency check, no
 *       repository.save, no outbox event.
 *   <li>In-range / out-of-range distinction surfaces correctly.
 *   <li>TVL share calculation lines up with the mixed-unit assumption.
 *   <li>Fee-per-day projection respects in-range gating.
 *   <li>Warnings emit for: out-of-range, high concentration, unused funds.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PreviewAddLiquidityServiceTest {

    @Mock
    private LiquidityPoolRepository poolRepository;
    @Mock
    private PoolBinRepository poolBinRepository;
    @Mock
    private LpPositionRepository positionRepository;
    @Mock
    private PositionBinRepository positionBinRepository;
    @Mock
    private TokenServiceClient tokenServiceClient;
    @Mock
    private UserServiceClient userServiceClient;
    @Mock
    private OutboxService outbox;
    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private LiquidityService liquidityService;

    private static final UUID POOL_ID = UUID.randomUUID();
    private static final UUID TOKEN_X_ID = UUID.randomUUID();
    private static final UUID TOKEN_Y_ID = UUID.randomUUID();

    private LiquidityPool pool;

    @BeforeEach
    void setUp() {
        pool = LiquidityPool.builder()
                .id(POOL_ID)
                .tokenXId(TOKEN_X_ID)
                .tokenYId(TOKEN_Y_ID)
                .binStep(10)
                .baseFeeBps(30)
                .maxVariableFeeBps(100)
                .volatilityAccumulator(0)
                .activeBinId(100)
                .basePrice(BigDecimal.ONE)
                .protocolFeePct(20)
                .totalTvlX(10_000_000L)
                .totalTvlY(10_000_000L)
                .volume24h(5_000_000L)
                .totalFeesCollectedX(0)
                .totalFeesCollectedY(0)
                .status(PoolStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("in-range balanced add: inRange=true, no out-of-range warning, fee projection > 0")
    void inRangeBalancedAdd() {
        when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
        when(poolBinRepository.findByPoolIdAndBinId(eq(POOL_ID), anyInt())).thenReturn(Optional.empty());

        // Range straddles activeBinId=100
        AddLiquidityRequest req = new AddLiquidityRequest(
                POOL_ID, 100_000, 100_000, 95, 105, LiquidityStrategy.SPOT, null);
        PreviewAddLiquidityResponse resp = liquidityService.previewAddLiquidity(req);

        assertNotNull(resp);
        assertTrue(resp.inRange(), "[95..105] straddles activeBin=100");
        assertEquals(0, resp.priceImpactBps(),
                "in-range adds don't shift the active price");
        assertEquals(10_000_000L, resp.tvlBeforeX());
        assertEquals(10_000_000L, resp.tvlBeforeY());
        assertTrue(resp.tvlAfterX() >= resp.tvlBeforeX());
        assertTrue(resp.tvlAfterY() >= resp.tvlBeforeY());
        assertTrue(resp.binAllocations().size() > 0, "must allocate to at least one bin");

        // Fee projection: in-range + non-zero volume + non-zero share → > 0
        assertTrue(resp.estimatedFeesPerDayY() > 0,
                "in-range position with volume should earn projected fees");

        // No "out of range" warning for in-range position
        assertFalse(resp.warnings().stream().anyMatch(w -> w.contains("не включает активный бин")),
                "in-range position should NOT have out-of-range warning");

        // Read-only: zero write-path side effects
        verify(poolRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(positionRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(positionBinRepository, never()).saveAll(org.mockito.ArgumentMatchers.any());
        verify(outbox, never()).append(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(tokenServiceClient, never()).deductBalance(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("out-of-range add: inRange=false, warning emitted, fee projection = 0")
    void outOfRangeAdd() {
        when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
        // Range fully above active — computeBinAmounts goes to the
        // X-only branch which doesn't touch composition-factor lookup,
        // so no poolBinRepository stub is needed here (Mockito returns
        // Optional.empty() by default).

        // Range [110..115] sits entirely above activeBinId=100
        AddLiquidityRequest req = new AddLiquidityRequest(
                POOL_ID, 100_000, 100_000, 110, 115, LiquidityStrategy.SPOT, null);
        PreviewAddLiquidityResponse resp = liquidityService.previewAddLiquidity(req);

        assertFalse(resp.inRange(), "[110..115] is entirely above activeBin=100");
        assertTrue(resp.priceImpactBps() > 0,
                "out-of-range add gets a non-zero heuristic impact");
        assertEquals(0L, resp.estimatedFeesPerDayY(),
                "out-of-range positions earn no fees until price re-enters");
        assertTrue(resp.warnings().stream().anyMatch(w -> w.contains("не включает активный бин")),
                "out-of-range position must surface the warning");
    }

    @Test
    @DisplayName("tiny share: TVL share well under 1%, no concentration warning")
    void tinyShare() {
        when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
        when(poolBinRepository.findByPoolIdAndBinId(eq(POOL_ID), anyInt())).thenReturn(Optional.empty());

        // Pool has 20M total TVL, user adds 2k — should be ~0.01%
        AddLiquidityRequest req = new AddLiquidityRequest(
                POOL_ID, 1_000, 1_000, 95, 105, LiquidityStrategy.SPOT, null);
        PreviewAddLiquidityResponse resp = liquidityService.previewAddLiquidity(req);

        assertTrue(resp.tvlSharePct() < 1.0, "tiny share should be well under 1%, was " + resp.tvlSharePct());
        assertFalse(resp.warnings().stream().anyMatch(w -> w.contains("большая концентрация")),
                "small share should NOT trigger concentration warning");
    }

    @Test
    @DisplayName("large share: TVL share > 10%, concentration warning emitted")
    void largeShare() {
        // Pool with tiny existing TVL so user's add dominates
        pool.setTotalTvlX(10_000L);
        pool.setTotalTvlY(10_000L);
        when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
        when(poolBinRepository.findByPoolIdAndBinId(eq(POOL_ID), anyInt())).thenReturn(Optional.empty());

        // User dumps 1M into a pool with 20k existing → ~98%
        AddLiquidityRequest req = new AddLiquidityRequest(
                POOL_ID, 1_000_000, 1_000_000, 95, 105, LiquidityStrategy.SPOT, null);
        PreviewAddLiquidityResponse resp = liquidityService.previewAddLiquidity(req);

        assertTrue(resp.tvlSharePct() > 10.0,
                "large share should be > 10%, was " + resp.tvlSharePct());
        assertTrue(resp.warnings().stream().anyMatch(w -> w.contains("большая концентрация")),
                "large share must trigger concentration warning");
    }

    @Test
    @DisplayName("single-bin position at activeBinId: 1 allocation, in-range")
    void singleBinAtActive() {
        when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
        when(poolBinRepository.findByPoolIdAndBinId(eq(POOL_ID), anyInt())).thenReturn(Optional.empty());

        AddLiquidityRequest req = new AddLiquidityRequest(
                POOL_ID, 100_000, 100_000, 100, 100, LiquidityStrategy.SPOT, null);
        PreviewAddLiquidityResponse resp = liquidityService.previewAddLiquidity(req);

        assertTrue(resp.inRange(), "single bin at activeBinId is in-range");
        assertEquals(1, resp.binAllocations().size(), "exactly 1 bin allocated");
        assertEquals(100, resp.binAllocations().get(0).binId());
    }

    @Test
    @DisplayName("multi-bin CURVE: allocations weighted toward active bin")
    void multiBinCurve() {
        when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
        when(poolBinRepository.findByPoolIdAndBinId(eq(POOL_ID), anyInt())).thenReturn(Optional.empty());

        AddLiquidityRequest req = new AddLiquidityRequest(
                POOL_ID, 100_000, 100_000, 90, 110, LiquidityStrategy.CURVE, null);
        PreviewAddLiquidityResponse resp = liquidityService.previewAddLiquidity(req);

        assertTrue(resp.inRange());
        assertTrue(resp.binAllocations().size() > 1, "CURVE over 21-bin span produces multiple allocations");

        // CURVE concentrates around active bin — the center bin should have
        // a larger liquidity share than an edge bin
        long activeBinShares = resp.binAllocations().stream()
                .filter(a -> a.binId() == 100)
                .mapToLong(a -> a.liquidityShares())
                .findFirst()
                .orElse(0L);
        long edgeBinShares = resp.binAllocations().stream()
                .filter(a -> a.binId() == 90)
                .mapToLong(a -> a.liquidityShares())
                .findFirst()
                .orElse(0L);
        assertTrue(activeBinShares > edgeBinShares,
                "CURVE: center bin should weight higher than edge bin");
    }

    @Test
    @DisplayName("all-X (one-sided above active): only X-side bins get allocated, Y dust")
    void oneSidedX() {
        when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
        // Above-active branch doesn't touch composition-factor lookup;
        // no poolBinRepository stub required.

        // Range fully above active — every bin holds X only (canonical DLMM)
        // User sends X+Y but only X side is actually consumable; the extra
        // Y becomes unused-funds warning.
        AddLiquidityRequest req = new AddLiquidityRequest(
                POOL_ID, 100_000, 100_000, 105, 110, LiquidityStrategy.SPOT, null);
        PreviewAddLiquidityResponse resp = liquidityService.previewAddLiquidity(req);

        assertFalse(resp.inRange());
        // Above-active bins hold X only; Y deposit should stay 0
        assertEquals(0L, resp.depositedY(),
                "all-above-active bins hold X only — Y deposit must be 0");
        assertTrue(resp.depositedX() > 0, "X must be deposited above-active");
        assertTrue(resp.warnings().stream().anyMatch(w -> w.contains("не будет использована")),
                "unused-Y must surface as warning");
    }

    @Test
    @DisplayName("invalid bin range: throws InvalidBinRangeException")
    void invalidBinRangeThrows() {
        when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));

        AddLiquidityRequest req = new AddLiquidityRequest(
                POOL_ID, 100_000, 100_000, 110, 100, LiquidityStrategy.SPOT, null);

        assertThrows(InvalidBinRangeException.class,
                () -> liquidityService.previewAddLiquidity(req));
    }

    @Test
    @DisplayName("pool not found: throws PoolNotFoundException")
    void poolNotFoundThrows() {
        when(poolRepository.findById(POOL_ID)).thenReturn(Optional.empty());

        AddLiquidityRequest req = new AddLiquidityRequest(
                POOL_ID, 100_000, 100_000, 95, 105, LiquidityStrategy.SPOT, null);

        assertThrows(PoolNotFoundException.class,
                () -> liquidityService.previewAddLiquidity(req));
    }

    @Test
    @DisplayName("pool paused: throws PoolNotActiveException")
    void pausedPoolThrows() {
        pool.setStatus(PoolStatus.PAUSED);
        when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));

        AddLiquidityRequest req = new AddLiquidityRequest(
                POOL_ID, 100_000, 100_000, 95, 105, LiquidityStrategy.SPOT, null);

        assertThrows(PoolNotActiveException.class,
                () -> liquidityService.previewAddLiquidity(req));
    }
}
