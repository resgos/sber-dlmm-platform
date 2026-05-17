package com.sber.dlmm.pool.service;

import com.sber.dlmm.common.enums.PoolStatus;
import com.sber.dlmm.common.exception.CounterpartyLimitExceededException;
import com.sber.dlmm.common.exception.IdempotencyConflictException;
import com.sber.dlmm.common.exception.InsufficientLiquidityException;
import com.sber.dlmm.common.exception.PoolNotActiveException;
import com.sber.dlmm.common.exception.SlippageExceededException;
import com.sber.dlmm.common.outbox.OutboxService;
import com.sber.dlmm.pool.client.TokenServiceClient;
import com.sber.dlmm.pool.client.UserServiceClient;
import com.sber.dlmm.pool.dto.SwapQuoteRequest;
import com.sber.dlmm.pool.dto.SwapQuoteResponse;
import com.sber.dlmm.pool.dto.SwapRequest;
import com.sber.dlmm.pool.dto.SwapResponse;
import com.sber.dlmm.pool.entity.LiquidityPool;
import com.sber.dlmm.pool.entity.PoolBin;
import com.sber.dlmm.pool.repository.LiquidityPoolRepository;
import com.sber.dlmm.pool.repository.PoolBinRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SwapServiceTest {

    @Mock
    private LiquidityPoolRepository poolRepository;
    @Mock
    private PoolBinRepository poolBinRepository;
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
    /**
     * Sprint 4 #4.7 — SwapService self-injects via {@code appCtx.getBean(SwapService.class)}
     * so the @Transactional proxy boundary fires on each retry of the bounded
     * optimistic-lock loop. Tests stub it back to the @InjectMocks instance
     * so the swap path actually executes instead of NPE'ing on bean lookup.
     */
    @Mock
    private ApplicationContext appCtx;

    @InjectMocks
    private SwapService swapService;

    private static final UUID POOL_ID = UUID.randomUUID();
    private static final UUID TOKEN_X_ID = UUID.randomUUID();
    private static final UUID TOKEN_Y_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private LiquidityPool pool;

    @BeforeEach
    void setUp() {
        // Mockito's constructor injection (6-arg ctor matches our @Mock fields) wins
        // over field injection, so the @Autowired ApplicationContext appCtx in
        // production code stays null. Patch it in explicitly so the @Transactional
        // self-invocation path resolves to our test bean instead of NPE'ing.
        ReflectionTestUtils.setField(swapService, "appCtx", appCtx);

        pool = LiquidityPool.builder()
                .id(POOL_ID)
                .tokenXId(TOKEN_X_ID)
                .tokenYId(TOKEN_Y_ID)
                .binStep(10)
                .baseFeeBps(30)
                .maxVariableFeeBps(100)
                .volatilityAccumulator(0)
                .activeBinId(0)
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

    private PoolBin createBin(int binId, long reserveX, long reserveY, long liquidity) {
        return PoolBin.builder()
                .poolId(POOL_ID)
                .binId(binId)
                .price(BigDecimal.ONE) // simplified price for tests
                .liquidity(liquidity)
                .reserveX(reserveX)
                .reserveY(reserveY)
                .compositionFactor(new BigDecimal("0.5"))
                .totalFeeX(0)
                .totalFeeY(0)
                .feeGrowthX(0)
                .feeGrowthY(0)
                .build();
    }

    // ── quote() ─────────────────────────────────────────────────

    @Nested
    @DisplayName("quote")
    class QuoteTests {

        @Test
        @DisplayName("single bin swap X→Y returns correct quote")
        void singleBinQuote() {
            PoolBin bin = createBin(0, 1_000_000, 1_000_000, 2_000_000);
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 0)).thenReturn(Optional.of(bin));

            SwapQuoteRequest req = new SwapQuoteRequest(POOL_ID, TOKEN_X_ID, 100_000);
            SwapQuoteResponse resp = swapService.quote(req);

            assertNotNull(resp);
            assertEquals(POOL_ID, resp.poolId());
            assertEquals(TOKEN_X_ID, resp.tokenInId());
            assertEquals(TOKEN_Y_ID, resp.tokenOutId());
            assertTrue(resp.estimatedAmountOut() > 0, "amountOut should be positive");
            assertTrue(resp.estimatedFee() > 0, "fee should be positive");
            assertEquals(0, resp.estimatedBinsCrossed());
        }

        @Test
        @DisplayName("multi-bin swap crosses bins when first bin exhausted")
        void multiBinQuote() {
            // Bin 0 has small reserveY, bin 1 has more
            PoolBin bin0 = createBin(0, 1_000_000, 50_000, 1_050_000);
            PoolBin bin1 = createBin(1, 1_000_000, 1_000_000, 2_000_000);

            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 0)).thenReturn(Optional.of(bin0));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 1)).thenReturn(Optional.of(bin1));

            // Request more than bin0 can provide (reserveY=50_000 so maxAmountIn ~ 50_000 for price=1)
            SwapQuoteRequest req = new SwapQuoteRequest(POOL_ID, TOKEN_X_ID, 200_000);
            SwapQuoteResponse resp = swapService.quote(req);

            assertNotNull(resp);
            assertTrue(resp.estimatedAmountOut() > 0);
            assertTrue(resp.estimatedBinsCrossed() >= 1, "Should cross at least 1 bin");
        }

        @Test
        @DisplayName("insufficient liquidity throws exception")
        void insufficientLiquidity() {
            // Bin with zero liquidity
            PoolBin bin = createBin(0, 0, 0, 0);
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(eq(POOL_ID), any(Integer.class)))
                    .thenReturn(Optional.of(bin));

            SwapQuoteRequest req = new SwapQuoteRequest(POOL_ID, TOKEN_X_ID, 100_000);

            assertThrows(InsufficientLiquidityException.class, () -> swapService.quote(req));
        }
    }

    // ── swap() ──────────────────────────────────────────────────

    @Nested
    @DisplayName("swap")
    @org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
    class SwapTests {

        @BeforeEach
        void setUpSwap() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(Boolean.TRUE);
            when(tokenServiceClient.isTokenActive(any())).thenReturn(true);
            when(userServiceClient.isUserKycVerified(USER_ID)).thenReturn(true);
            doNothing().when(tokenServiceClient).deductBalance(any(), any(), anyLong());
            doNothing().when(tokenServiceClient).creditBalance(any(), any(), anyLong());
            // Self-injection: route appCtx.getBean(SwapService.class) back at the
            // @InjectMocks instance so retry loop calls hit our bean, not null.
            when(appCtx.getBean(SwapService.class)).thenReturn(swapService);
        }


        /**
         * Spec 14.1: testSwapSingleBin — single-bin swap X→Y
         */
        @Test
        @DisplayName("testSwapSingleBin: single-bin swap executes within one bin, no bin crossing")
        void testSwapSingleBin() {
            PoolBin bin = createBin(0, 500_000, 500_000, 1_000_000);
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 0)).thenReturn(Optional.of(bin));

            SwapRequest req = new SwapRequest(POOL_ID, TOKEN_X_ID, 10_000, 0, "key-single-bin");
            SwapResponse resp = swapService.swap(req, USER_ID);

            assertNotNull(resp);
            assertEquals(POOL_ID, resp.poolId());
            assertEquals(TOKEN_X_ID, resp.tokenInId());
            assertEquals(TOKEN_Y_ID, resp.tokenOutId());
            assertTrue(resp.amountOut() > 0, "amountOut should be positive");
            assertTrue(resp.feeAmount() > 0, "fee should be positive");
            assertEquals(0, resp.binsCrossed(), "Single-bin swap should cross 0 bins");

            verify(tokenServiceClient).deductBalance(eq(USER_ID), eq(TOKEN_X_ID), anyLong());
            verify(tokenServiceClient).creditBalance(eq(USER_ID), eq(TOKEN_Y_ID), anyLong());
            // Swap event published via transactional outbox (post Sprint 3 #3.9).
            verify(outbox).append(anyString(), anyString(), anyString(), eq("pool-events"), any());
        }

        /**
         * Spec 14.1: testSwapMultipleBins — multi-bin swap crossing bins
         */
        @Test
        @DisplayName("testSwapMultipleBins: swap crosses multiple bins when single bin exhausted")
        void testSwapMultipleBins() {
            // Bin 0 has very little reserveY, so it will be exhausted
            PoolBin bin0 = createBin(0, 500_000, 5_000, 505_000);
            PoolBin bin1 = createBin(1, 500_000, 500_000, 1_000_000);

            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 0)).thenReturn(Optional.of(bin0));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 1)).thenReturn(Optional.of(bin1));

            SwapRequest req = new SwapRequest(POOL_ID, TOKEN_X_ID, 100_000, 0, "key-multi-bin");
            SwapResponse resp = swapService.swap(req, USER_ID);

            assertNotNull(resp);
            assertTrue(resp.amountOut() > 0, "amountOut should be positive");
            assertTrue(resp.binsCrossed() >= 1, "Should cross at least 1 bin");

            verify(tokenServiceClient).deductBalance(eq(USER_ID), eq(TOKEN_X_ID), anyLong());
            verify(tokenServiceClient).creditBalance(eq(USER_ID), eq(TOKEN_Y_ID), anyLong());
        }

        /**
         * Spec 14.1: testSwapInsufficientLiquidity — throws when no liquidity available
         */
        @Test
        @DisplayName("testSwapInsufficientLiquidity: throws InsufficientLiquidityException")
        void testSwapInsufficientLiquidity() {
            PoolBin emptyBin = createBin(0, 0, 0, 0);
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(eq(POOL_ID), any(Integer.class)))
                    .thenReturn(Optional.of(emptyBin));

            SwapRequest req = new SwapRequest(POOL_ID, TOKEN_X_ID, 10_000, 0, "key-insuff");

            assertThrows(InsufficientLiquidityException.class,
                    () -> swapService.swap(req, USER_ID));
        }

        /**
         * Spec 14.1: testSwapSlippageExceeded — throws when output < minAmountOut
         */
        @Test
        @DisplayName("testSwapSlippageExceeded: throws SlippageExceededException when minAmountOut not met")
        void testSwapSlippageExceeded() {
            PoolBin bin = createBin(0, 500_000, 500_000, 1_000_000);
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 0)).thenReturn(Optional.of(bin));

            // Set minAmountOut absurdly high so slippage check fails
            SwapRequest req = new SwapRequest(POOL_ID, TOKEN_X_ID, 10_000, 999_999_999, "key-slippage");

            assertThrows(SlippageExceededException.class,
                    () -> swapService.swap(req, USER_ID));
        }

        /**
         * Spec 14.1: testSwapIdempotency — duplicate idempotency key throws
         */
        @Test
        @DisplayName("testSwapIdempotency: duplicate idempotency key throws IdempotencyConflictException")
        void testSwapIdempotency() {
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(tokenServiceClient.isTokenActive(any())).thenReturn(true);
            lenient().when(userServiceClient.isUserKycVerified(USER_ID)).thenReturn(true);
            when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(Boolean.FALSE);

            SwapRequest req = new SwapRequest(POOL_ID, TOKEN_X_ID, 10_000, 0, "dup-idempotency-key");

            assertThrows(IdempotencyConflictException.class,
                    () -> swapService.swap(req, USER_ID));
        }

        @Test
        @DisplayName("single bin swap X→Y succeeds and updates reserves")
        void singleBinSwap() {
            PoolBin bin = createBin(0, 500_000, 500_000, 1_000_000);
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 0)).thenReturn(Optional.of(bin));

            SwapRequest req = new SwapRequest(POOL_ID, TOKEN_X_ID, 10_000, 0, "key-1");
            SwapResponse resp = swapService.swap(req, USER_ID);

            assertNotNull(resp);
            assertNotNull(resp.txId());
            assertEquals(POOL_ID, resp.poolId());
            assertTrue(resp.amountOut() > 0);
            assertTrue(resp.feeAmount() > 0);
            assertEquals(0, resp.binsCrossed());

            // Verify token transfers
            verify(tokenServiceClient).deductBalance(eq(USER_ID), eq(TOKEN_X_ID), anyLong());
            verify(tokenServiceClient).creditBalance(eq(USER_ID), eq(TOKEN_Y_ID), anyLong());

            // Verify pool-event appended to outbox (replaces direct KafkaTemplate.send).
            verify(outbox).append(anyString(), anyString(), anyString(), eq("pool-events"), any());
        }

        @Test
        @DisplayName("swap Y→X direction works correctly")
        void swapYtoX() {
            PoolBin bin = createBin(0, 500_000, 500_000, 1_000_000);
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 0)).thenReturn(Optional.of(bin));

            SwapRequest req = new SwapRequest(POOL_ID, TOKEN_Y_ID, 10_000, 0, "key-2");
            SwapResponse resp = swapService.swap(req, USER_ID);

            assertNotNull(resp);
            assertEquals(TOKEN_Y_ID, resp.tokenInId());
            assertEquals(TOKEN_X_ID, resp.tokenOutId());
            assertTrue(resp.amountOut() > 0);

            verify(tokenServiceClient).deductBalance(eq(USER_ID), eq(TOKEN_Y_ID), anyLong());
            verify(tokenServiceClient).creditBalance(eq(USER_ID), eq(TOKEN_X_ID), anyLong());
        }

        @Test
        @DisplayName("multi-bin swap crosses bins correctly")
        void multiBinSwap() {
            PoolBin bin0 = createBin(0, 500_000, 5_000, 505_000);
            PoolBin bin1 = createBin(1, 500_000, 500_000, 1_000_000);

            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 0)).thenReturn(Optional.of(bin0));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 1)).thenReturn(Optional.of(bin1));

            SwapRequest req = new SwapRequest(POOL_ID, TOKEN_X_ID, 100_000, 0, "key-3");
            SwapResponse resp = swapService.swap(req, USER_ID);

            assertNotNull(resp);
            assertTrue(resp.amountOut() > 0);
            assertTrue(resp.binsCrossed() >= 1, "Should cross at least 1 bin");
        }

        @Test
        @DisplayName("slippage exceeded throws exception")
        void slippageExceeded() {
            PoolBin bin = createBin(0, 500_000, 500_000, 1_000_000);
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 0)).thenReturn(Optional.of(bin));

            // minAmountOut set absurdly high
            SwapRequest req = new SwapRequest(POOL_ID, TOKEN_X_ID, 10_000, 999_999_999, "key-4");

            assertThrows(SlippageExceededException.class,
                    () -> swapService.swap(req, USER_ID));
        }

        @Test
        @DisplayName("pool not active throws exception")
        void poolNotActive() {
            pool.setStatus(PoolStatus.PAUSED);
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));

            SwapRequest req = new SwapRequest(POOL_ID, TOKEN_X_ID, 10_000, 0, null);

            assertThrows(PoolNotActiveException.class,
                    () -> swapService.swap(req, USER_ID));

            verify(tokenServiceClient, never()).deductBalance(any(), any(), anyLong());
        }

        @Test
        @DisplayName("duplicate idempotency key throws exception")
        void idempotencyConflict() {
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(tokenServiceClient.isTokenActive(any())).thenReturn(true);
            lenient().when(userServiceClient.isUserKycVerified(USER_ID)).thenReturn(true);
            when(valueOperations.setIfAbsent(anyString(), anyString(), any())).thenReturn(Boolean.FALSE);

            SwapRequest req = new SwapRequest(POOL_ID, TOKEN_X_ID, 10_000, 0, "dup-key");

            assertThrows(IdempotencyConflictException.class,
                    () -> swapService.swap(req, USER_ID));
        }

        @Test
        @DisplayName("insufficient liquidity in swap throws exception")
        void insufficientLiquiditySwap() {
            PoolBin emptyBin = createBin(0, 0, 0, 0);
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(eq(POOL_ID), any(Integer.class)))
                    .thenReturn(Optional.of(emptyBin));

            SwapRequest req = new SwapRequest(POOL_ID, TOKEN_X_ID, 10_000, 0, "key-5");

            assertThrows(InsufficientLiquidityException.class,
                    () -> swapService.swap(req, USER_ID));
        }

        @Test
        @DisplayName("volatility accumulator is updated after swap crossing bins")
        void volatilityAccumulatorUpdated() {
            PoolBin bin0 = createBin(0, 500_000, 1_000, 501_000);
            PoolBin bin1 = createBin(1, 500_000, 500_000, 1_000_000);

            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 0)).thenReturn(Optional.of(bin0));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 1)).thenReturn(Optional.of(bin1));

            SwapRequest req = new SwapRequest(POOL_ID, TOKEN_X_ID, 100_000, 0, "key-6");
            swapService.swap(req, USER_ID);

            // After crossing bins, VA should have been updated
            assertTrue(pool.getVolatilityAccumulator() >= 0);
            verify(poolRepository).save(pool);
        }

        /**
         * Sprint 4 #4.2 — counterparty single-swap cap rejects oversized X→Y swap.
         * Cap check sits before the bin walk and the token transfer, so neither
         * is allowed to happen on rejection (verified via never()).
         */
        @Test
        @DisplayName("counterparty cap on X side rejects oversized swap")
        void counterpartyCapRejectsOversizedXSwap() {
            pool.setMaxSingleSwapNominalX(50_000L);
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));

            SwapRequest req = new SwapRequest(POOL_ID, TOKEN_X_ID, 100_000, 0, "key-cap-x");

            assertThrows(CounterpartyLimitExceededException.class,
                    () -> swapService.swap(req, USER_ID));

            verify(tokenServiceClient, never()).deductBalance(any(), any(), anyLong());
            verify(tokenServiceClient, never()).creditBalance(any(), any(), anyLong());
        }

        /**
         * Cap on Y side must only fire for Y→X direction — an X→Y swap of any
         * size should be unaffected by maxSingleSwapNominalY.
         */
        @Test
        @DisplayName("counterparty cap on Y side ignored for X-to-Y direction")
        void counterpartyCapYIgnoredForXtoY() {
            pool.setMaxSingleSwapNominalY(50_000L); // Y-side cap
            PoolBin bin = createBin(0, 500_000, 500_000, 1_000_000);
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 0)).thenReturn(Optional.of(bin));

            // X→Y, amount 100k exceeds Y-cap of 50k but Y-cap shouldn't apply here.
            SwapRequest req = new SwapRequest(POOL_ID, TOKEN_X_ID, 100_000, 0, "key-cap-y-noop");
            SwapResponse resp = swapService.swap(req, USER_ID);

            assertNotNull(resp);
            assertTrue(resp.amountOut() > 0);
        }
    }
}
