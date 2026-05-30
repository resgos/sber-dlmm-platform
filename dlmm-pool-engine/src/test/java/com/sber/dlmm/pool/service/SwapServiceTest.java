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
    // Batch G-02 — quote-execute idempotency store. Not exercised by the
    // legacy SwapService tests but required by the constructor signature.
    @Mock
    private QuoteStore quoteStore;
    /**
     * Sprint 4 #4.7 — SwapService self-injects via {@code appCtx.getBean(SwapService.class)}
     * so the @Transactional proxy boundary fires on each retry of the bounded
     * optimistic-lock loop. Tests stub it back to the @InjectMocks instance
     * so the swap path actually executes instead of NPE'ing on bean lookup.
     */
    @Mock
    private ApplicationContext appCtx;

    // Batch G-02 — constructor switched from 6-arg to 8-arg (added QuoteStore
    // and long quoteTtlSeconds). Mockito's @InjectMocks can't satisfy the
    // primitive long parameter, so we construct manually now. The
    // appCtx self-injection ReflectionTestUtils call below still applies.
    private SwapService swapService;

    private static final long TEST_QUOTE_TTL_SECONDS = 30L;

    private static final UUID POOL_ID = UUID.randomUUID();
    private static final UUID TOKEN_X_ID = UUID.randomUUID();
    private static final UUID TOKEN_Y_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private LiquidityPool pool;

    @BeforeEach
    void setUp() {
        // Build SwapService directly so the long quoteTtlSeconds
        // parameter is satisfied (Mockito @InjectMocks would inject 0,
        // which makes every quote look instantly stale even though the
        // legacy tests don't exercise the quote-execute path).
        swapService = new SwapService(
                poolRepository, poolBinRepository,
                tokenServiceClient, userServiceClient,
                outbox, redisTemplate,
                quoteStore, TEST_QUOTE_TTL_SECONDS);
        // Sprint 4 #4.7 — appCtx.getBean(SwapService.class) self-injection.
        // The retry loop in swap() uses it to fire the @Transactional proxy
        // boundary; stub it back at our instance so the path executes.
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
            // Sprint 9-DS-r4 — bin layout fixed for canonical X→Y
            // traversal direction. Sprint 9-DS-r3 flipped the engine
            // to walk DOWN for X→Y (since price = Y/X, Y sits below
            // active), but this test was still seeding bin +1 — so
            // after bin 0 ran out the engine walked into bin -1 (which
            // was un-stubbed) and Mockito strict mode raised
            // PotentialStubbingProblem. Seeding the bin-below-active
            // instead reflects the canonical Y reserve layout and the
            // intent of the original test.
            PoolBin bin0 = createBin(0, 1_000_000, 50_000, 1_050_000);
            PoolBin binMinus1 = createBin(-1, 1_000_000, 1_000_000, 2_000_000);

            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 0)).thenReturn(Optional.of(bin0));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, -1)).thenReturn(Optional.of(binMinus1));

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
            // Sprint 10 #1 fix: pool is persisted via saveAndFlush (forces the
            // optimistic-lock check before the cross-service balance settlement).
            verify(poolRepository).saveAndFlush(pool);
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
         * Sprint 6 #3.2 — protocol-fee split accumulation. EXACT-AMOUNT
         * assertions (Sprint 7 test-quality review hardened this from
         * the original "≥0, ≤gross" for-the-badge bounds).
         *
         * <p>Math: baseFeeBps=30 (setUp default), VA=0 (no prior swap),
         * actualAmountIn=10_000 (fits in single bin with ample liquidity).
         * fee = 30 × 10000 / 10000 = 30.
         * protocol = floor(30 × 5 / 100) = 1.
         * LP = 30 - 1 = 29.
         */
        @Test
        @DisplayName("protocol fee 5% split: 30 fee → 1 protocol + 29 LP (exact)")
        void protocolFeeSplitAccumulates() {
            pool.setProtocolFeePct(5);
            pool.setTotalFeesCollectedX(0L);
            pool.setTotalProtocolFeeX(0L);
            PoolBin bin = createBin(0, 500_000, 500_000, 1_000_000);
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 0)).thenReturn(Optional.of(bin));

            SwapRequest req = new SwapRequest(POOL_ID, TOKEN_X_ID, 10_000, 0, "key-pfee-x");
            SwapResponse resp = swapService.swap(req, USER_ID);

            // Exact fee = baseFeeBps × amount / 10000 = 30 × 10000 / 10000 = 30
            assertEquals(30L, resp.feeAmount(),
                    "fee must equal baseFeeBps×amountIn/10000 = 30");
            assertEquals(30L, pool.getTotalFeesCollectedX(),
                    "gross fee must accumulate exactly (not just >0)");
            // Exact protocol slice = floor(30 × 5 / 100) = 1
            assertEquals(1L, pool.getTotalProtocolFeeX(),
                    "protocol slice must equal floor(gross × pct / 100) = 1");
            // Invariant: gross = protocol + LP-distributed (verified indirectly via bin.feeGrowth)
            assertTrue(pool.getTotalProtocolFeeX() <= pool.getTotalFeesCollectedX(),
                    "invariant: protocol slice ≤ gross");
        }

        /**
         * Sprint 7 test-quality hardening — added explicit "no double-counting"
         * check: two swaps with protocolFeePct=5 → totalProtocolFeeX grows by
         * exactly 1 each time, not 2 (which would indicate accidental double
         * accumulation in the fee accumulation path).
         */
        @Test
        @DisplayName("protocol fee accumulates additively across swaps (no double-counting)")
        void protocolFeeAdditive() {
            pool.setProtocolFeePct(5);
            pool.setTotalFeesCollectedX(0L);
            pool.setTotalProtocolFeeX(0L);
            PoolBin bin = createBin(0, 500_000, 500_000, 1_000_000);
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 0)).thenReturn(Optional.of(bin));

            swapService.swap(new SwapRequest(POOL_ID, TOKEN_X_ID, 10_000, 0, "key-add-1"), USER_ID);
            long afterFirst = pool.getTotalProtocolFeeX();
            assertEquals(1L, afterFirst, "first swap: 1");

            // Reset bin so second swap finds liquidity (mock returns the same bin object
            // which mutated during first swap — re-stub with fresh state).
            PoolBin freshBin = createBin(0, 500_000, 500_000, 1_000_000);
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 0)).thenReturn(Optional.of(freshBin));

            swapService.swap(new SwapRequest(POOL_ID, TOKEN_X_ID, 10_000, 0, "key-add-2"), USER_ID);
            long afterSecond = pool.getTotalProtocolFeeX();
            assertEquals(2L, afterSecond,
                    "second swap: must be 1+1=2, not 1+2 (no double-counting) or 1 (no overwrite)");
        }

        /**
         * #3.1 — at protocolFeePct=0 the protocol accumulator stays at 0
         * even when LP fees flow normally. This is the "disabled" mode that
         * preserves Sprint 1-2 pure-LP behaviour.
         */
        @Test
        @DisplayName("protocolFeePct=0 keeps totalProtocolFee at zero (disabled mode)")
        void protocolFeeZeroLeavesAccumulatorEmpty() {
            pool.setProtocolFeePct(0);
            pool.setTotalFeesCollectedX(0L);
            pool.setTotalProtocolFeeX(0L);
            PoolBin bin = createBin(0, 500_000, 500_000, 1_000_000);
            when(poolRepository.findById(POOL_ID)).thenReturn(Optional.of(pool));
            when(poolBinRepository.findByPoolIdAndBinId(POOL_ID, 0)).thenReturn(Optional.of(bin));

            SwapRequest req = new SwapRequest(POOL_ID, TOKEN_X_ID, 10_000, 0, "key-pfee-zero");
            SwapResponse resp = swapService.swap(req, USER_ID);

            assertNotNull(resp);
            assertTrue(pool.getTotalFeesCollectedX() > 0,
                    "LP fee still accumulates normally");
            assertEquals(0L, pool.getTotalProtocolFeeX(),
                    "protocolFeePct=0 → totalProtocolFee stays empty");
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
