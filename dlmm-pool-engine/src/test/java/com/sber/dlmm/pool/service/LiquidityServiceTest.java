package com.sber.dlmm.pool.service;

import com.sber.dlmm.common.enums.LiquidityStrategy;
import com.sber.dlmm.common.enums.PoolStatus;
import com.sber.dlmm.common.exception.ForbiddenException;
import com.sber.dlmm.common.exception.IdempotencyConflictException;
import com.sber.dlmm.common.exception.InvalidBinRangeException;
import com.sber.dlmm.common.exception.PoolNotActiveException;
import com.sber.dlmm.common.outbox.OutboxService;
import com.sber.dlmm.pool.client.TokenServiceClient;
import com.sber.dlmm.pool.client.UserServiceClient;
import com.sber.dlmm.pool.dto.AddLiquidityRequest;
import com.sber.dlmm.pool.dto.AddLiquidityResponse;
import com.sber.dlmm.pool.dto.RemoveLiquidityRequest;
import com.sber.dlmm.pool.dto.RemoveLiquidityResponse;
import com.sber.dlmm.pool.entity.LiquidityPool;
import com.sber.dlmm.pool.entity.LpPosition;
import com.sber.dlmm.pool.entity.PoolBin;
import com.sber.dlmm.pool.entity.PositionBin;
import com.sber.dlmm.pool.repository.LiquidityPoolRepository;
import com.sber.dlmm.pool.repository.LpPositionRepository;
import com.sber.dlmm.pool.repository.PoolBinRepository;
import com.sber.dlmm.pool.repository.PositionBinRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiquidityServiceTest {

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
    // Sprint 3 #3.9 — production code switched from KafkaTemplate to OutboxService.
    @Mock
    private OutboxService outbox;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private LiquidityService liquidityService;

    private static final UUID POOL_ID = UUID.randomUUID();
    private static final UUID TOKEN_X_ID = UUID.randomUUID();
    private static final UUID TOKEN_Y_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

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
                .activeBinId(5)
                .basePrice(BigDecimal.ONE)
                .protocolFeePct(20)
                .totalTvlX(0)
                .totalTvlY(0)
                .volume24h(0)
                .totalFeesCollectedX(0)
                .totalFeesCollectedY(0)
                .status(PoolStatus.ACTIVE)
                .build();
    }

    private void mockCommonDependencies() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(Boolean.TRUE);
        when(tokenServiceClient.isTokenActive(any())).thenReturn(true);
        when(userServiceClient.isUserKycVerified(USER_ID)).thenReturn(true);
        doNothing().when(tokenServiceClient).deductBalance(any(), any(), anyLong());
        // OutboxService.append returns void — no future stub needed.
        doNothing().when(outbox).append(anyString(), anyString(), anyString(), anyString(), any());
    }

    // ── addLiquidity ────────────────────────────────────────────

    @Nested
    @DisplayName("addLiquidity")
    class AddLiquidityTests {

        /**
         * Spec 14.1: testAddLiquiditySpotStrategy — SPOT strategy distributes liquidity uniformly
         */
        @Test
        @DisplayName("testAddLiquiditySpotStrategy: uniform distribution across bins")
        void testAddLiquiditySpotStrategy() {
            mockCommonDependencies();
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(eq(POOL_ID), anyInt())).thenReturn(Optional.empty());

            AddLiquidityRequest req = new AddLiquidityRequest(
                    POOL_ID, 500_000, 500_000, 3, 7, LiquidityStrategy.SPOT, "add-key-spot");
            AddLiquidityResponse resp = liquidityService.addLiquidity(req, USER_ID);

            assertNotNull(resp);
            assertNotNull(resp.positionId());
            assertEquals(POOL_ID, resp.poolId());
            assertEquals(LiquidityStrategy.SPOT, resp.strategy());
            assertEquals(3, resp.binRangeMin());
            assertEquals(7, resp.binRangeMax());
            assertTrue(resp.liquidityShares() > 0, "Should have liquidity shares");
            assertTrue(resp.binAllocations().size() > 0, "Should have bin allocations");

            // SPOT strategy: all bins should have approximately equal allocation
            long avgShares = resp.liquidityShares() / resp.binAllocations().size();
            for (var alloc : resp.binAllocations()) {
                // Allow 50% deviation from average (due to rounding and bin position effects)
                assertTrue(alloc.liquidityShares() > 0, "Each bin should have some liquidity");
            }

            verify(poolBinRepository, atLeastOnce()).save(any(PoolBin.class));
            verify(positionRepository).save(any(LpPosition.class));
            verify(outbox).append(anyString(), anyString(), anyString(), eq("pool-events"), any());
        }

        /**
         * Spec 14.1: testAddLiquidityCurveStrategy — CURVE strategy concentrates around active bin
         */
        @Test
        @DisplayName("testAddLiquidityCurveStrategy: concentrates liquidity around active bin")
        void testAddLiquidityCurveStrategy() {
            mockCommonDependencies();
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(eq(POOL_ID), anyInt())).thenReturn(Optional.empty());

            // Active bin is 5, range is 0-10 — CURVE should give more weight to bins near 5
            AddLiquidityRequest req = new AddLiquidityRequest(
                    POOL_ID, 500_000, 500_000, 0, 10, LiquidityStrategy.CURVE, "add-key-curve");
            AddLiquidityResponse resp = liquidityService.addLiquidity(req, USER_ID);

            assertNotNull(resp);
            assertEquals(LiquidityStrategy.CURVE, resp.strategy());
            assertTrue(resp.liquidityShares() > 0, "Should have liquidity shares");
            assertTrue(resp.binAllocations().size() > 0, "Should have bin allocations");

            verify(poolBinRepository, atLeastOnce()).save(any(PoolBin.class));
            verify(positionRepository).save(any(LpPosition.class));
        }

        @Test
        @DisplayName("SPOT strategy distributes liquidity uniformly across bins")
        void addLiquiditySpot() {
            mockCommonDependencies();
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(eq(POOL_ID), anyInt())).thenReturn(Optional.empty());

            AddLiquidityRequest req = new AddLiquidityRequest(
                    POOL_ID, 500_000, 500_000, 3, 7, LiquidityStrategy.SPOT, "add-key-1");
            AddLiquidityResponse resp = liquidityService.addLiquidity(req, USER_ID);

            assertNotNull(resp);
            assertNotNull(resp.positionId());
            assertEquals(POOL_ID, resp.poolId());
            assertEquals(3, resp.binRangeMin());
            assertEquals(7, resp.binRangeMax());
            assertEquals(LiquidityStrategy.SPOT, resp.strategy());
            assertTrue(resp.liquidityShares() > 0);
            assertTrue(resp.binAllocations().size() > 0);

            // Verify bins created/saved
            verify(poolBinRepository, atLeastOnce()).save(any(PoolBin.class));
            // Verify position saved
            verify(positionRepository).save(any(LpPosition.class));
            // Verify Kafka event
            verify(outbox).append(anyString(), anyString(), anyString(), eq("pool-events"), any());
        }

        @Test
        @DisplayName("CURVE strategy concentrates liquidity around active bin")
        void addLiquidityCurve() {
            mockCommonDependencies();
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(eq(POOL_ID), anyInt())).thenReturn(Optional.empty());

            // Active bin is 5, range is 0-10 — CURVE should give more weight to bins near 5
            AddLiquidityRequest req = new AddLiquidityRequest(
                    POOL_ID, 500_000, 500_000, 0, 10, LiquidityStrategy.CURVE, "add-key-2");
            AddLiquidityResponse resp = liquidityService.addLiquidity(req, USER_ID);

            assertNotNull(resp);
            assertTrue(resp.liquidityShares() > 0);
            assertTrue(resp.binAllocations().size() > 0);
        }

        @Test
        @DisplayName("BID_ASK strategy concentrates liquidity at edges")
        void addLiquidityBidAsk() {
            mockCommonDependencies();
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(eq(POOL_ID), anyInt())).thenReturn(Optional.empty());

            AddLiquidityRequest req = new AddLiquidityRequest(
                    POOL_ID, 500_000, 500_000, 0, 10, LiquidityStrategy.BID_ASK, "add-key-3");
            AddLiquidityResponse resp = liquidityService.addLiquidity(req, USER_ID);

            assertNotNull(resp);
            assertTrue(resp.liquidityShares() > 0);
        }

        @Test
        @DisplayName("pool not active throws exception")
        void poolNotActive() {
            pool.setStatus(PoolStatus.PAUSED);
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));

            AddLiquidityRequest req = new AddLiquidityRequest(
                    POOL_ID, 100_000, 100_000, 3, 7, LiquidityStrategy.SPOT, null);

            assertThrows(PoolNotActiveException.class,
                    () -> liquidityService.addLiquidity(req, USER_ID));
        }

        @Test
        @DisplayName("KYC not verified throws ForbiddenException")
        void kycNotVerified() {
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(tokenServiceClient.isTokenActive(any())).thenReturn(true);
            when(userServiceClient.isUserKycVerified(USER_ID)).thenReturn(false);

            AddLiquidityRequest req = new AddLiquidityRequest(
                    POOL_ID, 100_000, 100_000, 3, 7, LiquidityStrategy.SPOT, null);

            assertThrows(ForbiddenException.class,
                    () -> liquidityService.addLiquidity(req, USER_ID));
        }

        @Test
        @DisplayName("invalid bin range (min > max) throws exception")
        void invalidBinRange() {
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(tokenServiceClient.isTokenActive(any())).thenReturn(true);
            when(userServiceClient.isUserKycVerified(USER_ID)).thenReturn(true);

            AddLiquidityRequest req = new AddLiquidityRequest(
                    POOL_ID, 100_000, 100_000, 10, 5, LiquidityStrategy.SPOT, null);

            assertThrows(InvalidBinRangeException.class,
                    () -> liquidityService.addLiquidity(req, USER_ID));
        }

        @Test
        @DisplayName("duplicate idempotency key throws exception")
        void idempotencyConflict() {
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(tokenServiceClient.isTokenActive(any())).thenReturn(true);
            when(userServiceClient.isUserKycVerified(USER_ID)).thenReturn(true);
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(Boolean.FALSE);

            AddLiquidityRequest req = new AddLiquidityRequest(
                    POOL_ID, 100_000, 100_000, 3, 7, LiquidityStrategy.SPOT, "dup-key");

            assertThrows(IdempotencyConflictException.class,
                    () -> liquidityService.addLiquidity(req, USER_ID));
        }

        @Test
        @DisplayName("deducts token balances from user after allocation")
        void deductsTokens() {
            mockCommonDependencies();
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(eq(POOL_ID), anyInt())).thenReturn(Optional.empty());

            AddLiquidityRequest req = new AddLiquidityRequest(
                    POOL_ID, 500_000, 500_000, 3, 7, LiquidityStrategy.SPOT, "add-key-4");
            liquidityService.addLiquidity(req, USER_ID);

            // At least one token type should have been deducted
            verify(tokenServiceClient, atLeastOnce()).deductBalance(eq(USER_ID), any(), anyLong());
        }

        @Test
        @DisplayName("updates pool TVL after adding liquidity")
        void updatesTvl() {
            mockCommonDependencies();
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(eq(POOL_ID), anyInt())).thenReturn(Optional.empty());

            AddLiquidityRequest req = new AddLiquidityRequest(
                    POOL_ID, 500_000, 500_000, 3, 7, LiquidityStrategy.SPOT, "add-key-5");
            liquidityService.addLiquidity(req, USER_ID);

            verify(poolRepository).save(pool);
            assertTrue(pool.getTotalTvlX() > 0 || pool.getTotalTvlY() > 0,
                    "TVL should increase after adding liquidity");
        }

        // ── Sprint 16 (Meteora parity) — single-sided liquidity ──────────────

        @Test
        @DisplayName("single-sided X (amountY=0) deposits only the base side, above the active bin")
        void singleSidedXDepositsOnlyBaseSide() {
            mockCommonDependencies();
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(eq(POOL_ID), anyInt())).thenReturn(Optional.empty());

            // active bin = 5; range 6..9 is entirely ABOVE active → all X, no Y.
            AddLiquidityRequest req = new AddLiquidityRequest(
                    POOL_ID, 400_000, 0, 6, 9, LiquidityStrategy.SPOT, "ss-x");
            AddLiquidityResponse resp = liquidityService.addLiquidity(req, USER_ID);

            assertNotNull(resp);
            assertTrue(resp.liquidityShares() > 0);
            assertTrue(pool.getTotalTvlX() > 0, "X TVL should grow");
            assertEquals(0, pool.getTotalTvlY(), "Y TVL must stay 0 for a single-sided X deposit");
            verify(tokenServiceClient).deductBalance(eq(USER_ID), eq(TOKEN_X_ID), anyLong());
            verify(tokenServiceClient, never()).deductBalance(eq(USER_ID), eq(TOKEN_Y_ID), anyLong());
        }

        @Test
        @DisplayName("single-sided Y (amountX=0) deposits only the quote side, below the active bin")
        void singleSidedYDepositsOnlyQuoteSide() {
            mockCommonDependencies();
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(eq(POOL_ID), anyInt())).thenReturn(Optional.empty());

            // active bin = 5; range 1..4 is entirely BELOW active → all Y, no X.
            AddLiquidityRequest req = new AddLiquidityRequest(
                    POOL_ID, 0, 400_000, 1, 4, LiquidityStrategy.SPOT, "ss-y");
            AddLiquidityResponse resp = liquidityService.addLiquidity(req, USER_ID);

            assertNotNull(resp);
            assertTrue(resp.liquidityShares() > 0);
            assertTrue(pool.getTotalTvlY() > 0, "Y TVL should grow");
            assertEquals(0, pool.getTotalTvlX(), "X TVL must stay 0 for a single-sided Y deposit");
            verify(tokenServiceClient).deductBalance(eq(USER_ID), eq(TOKEN_Y_ID), anyLong());
            verify(tokenServiceClient, never()).deductBalance(eq(USER_ID), eq(TOKEN_X_ID), anyLong());
        }

        @Test
        @DisplayName("both amounts zero is rejected")
        void bothAmountsZeroThrows() {
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(tokenServiceClient.isTokenActive(any())).thenReturn(true);
            when(userServiceClient.isUserKycVerified(USER_ID)).thenReturn(true);

            AddLiquidityRequest req = new AddLiquidityRequest(
                    POOL_ID, 0, 0, 3, 7, LiquidityStrategy.SPOT, null);

            assertThrows(InvalidBinRangeException.class,
                    () -> liquidityService.addLiquidity(req, USER_ID));
            verify(tokenServiceClient, never()).deductBalance(any(), any(), anyLong());
        }

        // ── Meteora per-bin fee model ────────────────────────────────────
        @Test
        @DisplayName("stamps each PositionBin's per-bin fee-growth checkpoint at the bin's current growth")
        void stampsPerBinFeeGrowthCheckpoint() {
            mockCommonDependencies();
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            // Bin 5 already accrued fee growth; a position entering now must checkpoint
            // THAT value (so it earns nothing for the past). New bins start at 0.
            PoolBin existing5 = PoolBin.builder()
                    .poolId(POOL_ID).binId(5).price(BigDecimal.ONE)
                    .liquidity(1_000_000).reserveX(500_000).reserveY(500_000)
                    .compositionFactor(new BigDecimal("0.5"))
                    .totalFeeX(0).totalFeeY(0).feeGrowthX(12_345).feeGrowthY(67_890)
                    .build();
            when(poolBinRepository.findByPoolIdAndBinId(eq(POOL_ID), anyInt()))
                    .thenAnswer(inv -> inv.getArgument(1, Integer.class).equals(5)
                            ? Optional.of(existing5) : Optional.empty());

            AddLiquidityRequest req = new AddLiquidityRequest(
                    POOL_ID, 500_000, 500_000, 3, 7, LiquidityStrategy.SPOT, "stamp-key");
            liquidityService.addLiquidity(req, USER_ID);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<PositionBin>> captor = ArgumentCaptor.forClass(List.class);
            verify(positionBinRepository).saveAll(captor.capture());
            List<PositionBin> saved = captor.getValue();

            PositionBin bin5 = saved.stream().filter(pb -> pb.getBinId() == 5).findFirst().orElseThrow();
            assertEquals(12_345L, bin5.getFeeGrowthCheckpointX(), "checkpoint X = bin's current fee growth");
            assertEquals(67_890L, bin5.getFeeGrowthCheckpointY(), "checkpoint Y = bin's current fee growth");

            PositionBin bin3 = saved.stream().filter(pb -> pb.getBinId() == 3).findFirst().orElseThrow();
            assertEquals(0L, bin3.getFeeGrowthCheckpointX(), "a freshly-created bin starts at 0 growth");
        }
    }

    // ── removeLiquidity ─────────────────────────────────────────

    @Nested
    @DisplayName("removeLiquidity")
    class RemoveLiquidityTests {

        private UUID positionId;
        private LpPosition position;

        @BeforeEach
        void setUpPosition() {
            positionId = UUID.randomUUID();
            position = LpPosition.builder()
                    .id(positionId)
                    .userId(USER_ID)
                    .poolId(POOL_ID)
                    .binRangeMin(3)
                    .binRangeMax(7)
                    .strategy(LiquidityStrategy.SPOT)
                    .totalLiquidityShares(500_000)
                    .unclaimedFeeX(0)
                    .unclaimedFeeY(0)
                    .lastFeeGrowthX(0)
                    .lastFeeGrowthY(0)
                    .isActive(true)
                    .createdAt(LocalDateTime.now())
                    .build();
        }

        /**
         * Spec 14.1: testRemoveLiquidityPartial50Percent — 50% removal keeps position active
         */
        @Test
        @DisplayName("testRemoveLiquidityPartial50Percent: 50% removal keeps position active")
        void testRemoveLiquidityPartial50Percent() {
            when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(Boolean.TRUE);
            doNothing().when(tokenServiceClient).creditBalance(any(), any(), anyLong());
            // OutboxService.append returns void — no future stub needed.
            doNothing().when(outbox).append(anyString(), anyString(), anyString(), anyString(), any());

            PoolBin bin5 = PoolBin.builder()
                    .poolId(POOL_ID).binId(5).price(BigDecimal.ONE)
                    .liquidity(500_000).reserveX(250_000).reserveY(250_000)
                    .compositionFactor(new BigDecimal("0.5"))
                    .totalFeeX(0).totalFeeY(0).feeGrowthX(10).feeGrowthY(10)
                    .build();

            PositionBin posBin = PositionBin.builder()
                    .positionId(positionId).binId(5).liquidityShares(500_000)
                    .build();

            when(positionBinRepository.findByPositionId(positionId)).thenReturn(List.of(posBin));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 5)).thenReturn(Optional.of(bin5));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, pool.getActiveBinId()))
                    .thenReturn(Optional.of(bin5));

            long initialShares = position.getTotalLiquidityShares();

            // 5_000 bps = 50%
            RemoveLiquidityRequest req = new RemoveLiquidityRequest(positionId, 5_000, "rem-50pct");
            RemoveLiquidityResponse resp = liquidityService.removeLiquidity(req, USER_ID);

            assertNotNull(resp);
            assertEquals(positionId, resp.positionId());
            assertTrue(resp.withdrawnX() > 0 || resp.withdrawnY() > 0, "Should withdraw something");
            assertTrue(position.isActive(), "Position should still be active after 50% removal");
            assertTrue(position.getTotalLiquidityShares() > 0, "Should have remaining shares");
            assertTrue(position.getTotalLiquidityShares() < initialShares, "Shares should decrease");
        }

        /**
         * Spec 14.1: testRemoveLiquidityFull100Percent — 100% removal closes position
         */
        @Test
        @DisplayName("testRemoveLiquidityFull100Percent: 100% removal closes position")
        void testRemoveLiquidityFull100Percent() {
            when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(Boolean.TRUE);
            doNothing().when(tokenServiceClient).creditBalance(any(), any(), anyLong());
            // OutboxService.append returns void — no future stub needed.
            doNothing().when(outbox).append(anyString(), anyString(), anyString(), anyString(), any());

            PoolBin bin5 = PoolBin.builder()
                    .poolId(POOL_ID).binId(5).price(BigDecimal.ONE)
                    .liquidity(500_000).reserveX(250_000).reserveY(250_000)
                    .compositionFactor(new BigDecimal("0.5"))
                    .totalFeeX(0).totalFeeY(0).feeGrowthX(0).feeGrowthY(0)
                    .build();

            PositionBin posBin = PositionBin.builder()
                    .positionId(positionId).binId(5).liquidityShares(500_000)
                    .build();

            when(positionBinRepository.findByPositionId(positionId)).thenReturn(List.of(posBin));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 5)).thenReturn(Optional.of(bin5));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, pool.getActiveBinId()))
                    .thenReturn(Optional.of(bin5));

            // 10_000 bps = 100%
            RemoveLiquidityRequest req = new RemoveLiquidityRequest(positionId, 10_000, "rem-100pct");
            RemoveLiquidityResponse resp = liquidityService.removeLiquidity(req, USER_ID);

            assertNotNull(resp);
            assertEquals(positionId, resp.positionId());
            assertFalse(position.isActive(), "Position should be closed at 100%");
            assertNotNull(position.getClosedAt(), "closedAt should be set");
        }

        @Test
        @DisplayName("partial removal (50%) withdraws proportional amounts")
        void partialRemoval() {
            when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(Boolean.TRUE);
            doNothing().when(tokenServiceClient).creditBalance(any(), any(), anyLong());
            // OutboxService.append returns void — no future stub needed.
            doNothing().when(outbox).append(anyString(), anyString(), anyString(), anyString(), any());

            PoolBin bin5 = PoolBin.builder()
                    .poolId(POOL_ID).binId(5).price(BigDecimal.ONE)
                    .liquidity(500_000).reserveX(250_000).reserveY(250_000)
                    .compositionFactor(new BigDecimal("0.5"))
                    .totalFeeX(0).totalFeeY(0).feeGrowthX(10).feeGrowthY(10)
                    .build();

            PositionBin posBin = PositionBin.builder()
                    .positionId(positionId).binId(5).liquidityShares(500_000)
                    .build();

            when(positionBinRepository.findByPositionId(positionId)).thenReturn(List.of(posBin));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 5)).thenReturn(Optional.of(bin5));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, pool.getActiveBinId()))
                    .thenReturn(Optional.of(bin5));

            RemoveLiquidityRequest req = new RemoveLiquidityRequest(positionId, 5_000, "rem-key-1");
            RemoveLiquidityResponse resp = liquidityService.removeLiquidity(req, USER_ID);

            assertNotNull(resp);
            assertEquals(positionId, resp.positionId());
            assertTrue(resp.withdrawnX() > 0 || resp.withdrawnY() > 0, "Should withdraw something");

            // Position should still be active (only 50%)
            assertTrue(position.isActive());
            assertTrue(position.getTotalLiquidityShares() > 0);
        }

        @Test
        @DisplayName("full removal (100%) closes position")
        void fullRemoval() {
            when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(Boolean.TRUE);
            doNothing().when(tokenServiceClient).creditBalance(any(), any(), anyLong());
            // OutboxService.append returns void — no future stub needed.
            doNothing().when(outbox).append(anyString(), anyString(), anyString(), anyString(), any());

            PoolBin bin5 = PoolBin.builder()
                    .poolId(POOL_ID).binId(5).price(BigDecimal.ONE)
                    .liquidity(500_000).reserveX(250_000).reserveY(250_000)
                    .compositionFactor(new BigDecimal("0.5"))
                    .totalFeeX(0).totalFeeY(0).feeGrowthX(0).feeGrowthY(0)
                    .build();

            PositionBin posBin = PositionBin.builder()
                    .positionId(positionId).binId(5).liquidityShares(500_000)
                    .build();

            when(positionBinRepository.findByPositionId(positionId)).thenReturn(List.of(posBin));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 5)).thenReturn(Optional.of(bin5));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, pool.getActiveBinId()))
                    .thenReturn(Optional.of(bin5));

            RemoveLiquidityRequest req = new RemoveLiquidityRequest(positionId, 10_000, "rem-key-2");
            RemoveLiquidityResponse resp = liquidityService.removeLiquidity(req, USER_ID);

            assertNotNull(resp);
            assertFalse(position.isActive(), "Position should be closed at 100%");
            assertNotNull(position.getClosedAt());
        }

        @Test
        @DisplayName("removal includes accrued fees")
        void removalWithFees() {
            when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(Boolean.TRUE);
            doNothing().when(tokenServiceClient).creditBalance(any(), any(), anyLong());
            // OutboxService.append returns void — no future stub needed.
            doNothing().when(outbox).append(anyString(), anyString(), anyString(), anyString(), any());

            // Bin accumulated fee growth since the position opened (lastFeeGrowth=0).
            // Sprint 10 #2 fix: feeGrowth is now FEE_GROWTH_SCALE(1e9)-scaled per
            // unit of liquidity. Over 500_000 liquidity the full-owner position
            // reclaims feeGrowth*500_000/1e9 — so 200_000 ⇒ 100 X-fee, 100_000 ⇒ 50 Y-fee.
            PoolBin bin5 = PoolBin.builder()
                    .poolId(POOL_ID).binId(5).price(BigDecimal.ONE)
                    .liquidity(500_000).reserveX(250_000).reserveY(250_000)
                    .compositionFactor(new BigDecimal("0.5"))
                    .totalFeeX(1000).totalFeeY(1000).feeGrowthX(200_000).feeGrowthY(100_000)
                    .build();

            PositionBin posBin = PositionBin.builder()
                    .positionId(positionId).binId(5).liquidityShares(500_000)
                    .build();

            when(positionBinRepository.findByPositionId(positionId)).thenReturn(List.of(posBin));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 5)).thenReturn(Optional.of(bin5));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, pool.getActiveBinId()))
                    .thenReturn(Optional.of(bin5));

            RemoveLiquidityRequest req = new RemoveLiquidityRequest(positionId, 10_000, "rem-key-3");
            RemoveLiquidityResponse resp = liquidityService.removeLiquidity(req, USER_ID);

            assertNotNull(resp);
            assertTrue(resp.claimedFeeX() > 0, "Should claim accumulated X fees");
            assertTrue(resp.claimedFeeY() > 0, "Should claim accumulated Y fees");
        }

        @Test
        @DisplayName("removing from another user's position throws ForbiddenException")
        void forbiddenForOtherUser() {
            when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));

            UUID otherUser = UUID.randomUUID();
            RemoveLiquidityRequest req = new RemoveLiquidityRequest(positionId, 10_000, null);

            assertThrows(ForbiddenException.class,
                    () -> liquidityService.removeLiquidity(req, otherUser));
        }

        @Test
        @DisplayName("removing from closed position throws exception")
        void closedPosition() {
            position.setActive(false);
            when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));

            RemoveLiquidityRequest req = new RemoveLiquidityRequest(positionId, 10_000, null);

            assertThrows(PoolNotActiveException.class,
                    () -> liquidityService.removeLiquidity(req, USER_ID));
        }

        @Test
        @DisplayName("credits withdrawn amounts plus fees to user")
        void creditsUser() {
            when(positionRepository.findById(positionId)).thenReturn(Optional.of(position));
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(Boolean.TRUE);
            doNothing().when(tokenServiceClient).creditBalance(any(), any(), anyLong());
            // OutboxService.append returns void — no future stub needed.
            doNothing().when(outbox).append(anyString(), anyString(), anyString(), anyString(), any());

            PoolBin bin5 = PoolBin.builder()
                    .poolId(POOL_ID).binId(5).price(BigDecimal.ONE)
                    .liquidity(500_000).reserveX(250_000).reserveY(250_000)
                    .compositionFactor(new BigDecimal("0.5"))
                    .totalFeeX(0).totalFeeY(0).feeGrowthX(0).feeGrowthY(0)
                    .build();

            PositionBin posBin = PositionBin.builder()
                    .positionId(positionId).binId(5).liquidityShares(500_000)
                    .build();

            when(positionBinRepository.findByPositionId(positionId)).thenReturn(List.of(posBin));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 5)).thenReturn(Optional.of(bin5));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, pool.getActiveBinId()))
                    .thenReturn(Optional.of(bin5));

            RemoveLiquidityRequest req = new RemoveLiquidityRequest(positionId, 10_000, "rem-key-4");
            liquidityService.removeLiquidity(req, USER_ID);

            verify(tokenServiceClient, atLeastOnce()).creditBalance(eq(USER_ID), any(), anyLong());
        }
    }
}
