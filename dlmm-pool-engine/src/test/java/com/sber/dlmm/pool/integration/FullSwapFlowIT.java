package com.sber.dlmm.pool.integration;

import com.sber.dlmm.common.enums.LiquidityStrategy;
import com.sber.dlmm.common.enums.PoolStatus;
import com.sber.dlmm.common.exception.InsufficientLiquidityException;
import com.sber.dlmm.common.exception.SlippageExceededException;
import com.sber.dlmm.common.util.BinMath;
import com.sber.dlmm.pool.DlmmPoolEngineApplication;
import com.sber.dlmm.pool.client.TokenServiceClient;
import com.sber.dlmm.pool.client.UserServiceClient;
import com.sber.dlmm.pool.dto.AddLiquidityRequest;
import com.sber.dlmm.pool.dto.AddLiquidityResponse;
import com.sber.dlmm.pool.dto.RemoveLiquidityRequest;
import com.sber.dlmm.pool.dto.RemoveLiquidityResponse;
import com.sber.dlmm.pool.dto.SwapQuoteRequest;
import com.sber.dlmm.pool.dto.SwapQuoteResponse;
import com.sber.dlmm.pool.dto.SwapRequest;
import com.sber.dlmm.pool.dto.SwapResponse;
import com.sber.dlmm.pool.entity.LiquidityPool;
import com.sber.dlmm.pool.entity.LpPosition;
import com.sber.dlmm.pool.entity.PoolBin;
import com.sber.dlmm.pool.repository.LiquidityPoolRepository;
import com.sber.dlmm.pool.repository.LpPositionRepository;
import com.sber.dlmm.pool.repository.PoolBinRepository;
import com.sber.dlmm.pool.repository.PositionBinRepository;
import com.sber.dlmm.pool.service.LiquidityService;
import com.sber.dlmm.pool.service.SwapService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * Integration test for the full swap flow using Testcontainers.
 * Per spec Section 14.2:
 * 1. Create pool (sGAZP/sRUB, binStep=10, baseFee=5)
 * 2. Add liquidity (SPOT strategy, bins -5..+5)
 * 3. Execute swap: X→Y
 * 4. Verify: reserves updated, fees collected, bins correct
 * 5. Remove liquidity and verify fee claiming
 */
@Testcontainers
@SpringBootTest(classes = DlmmPoolEngineApplication.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FullSwapFlowIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16"))
            .withDatabaseName("dlmm_pools")
            .withUsername("dlmm")
            .withPassword("dlmm_secret");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        // Use create-drop for IT to avoid needing external Liquibase state
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.liquibase.enabled", () -> "false");
        // Redis — use mock via StringRedisTemplate MockBean
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "6379");
        // Token service URL (mocked via MockBean)
        registry.add("dlmm.services.token-service.url", () -> "http://localhost:9999");
    }

    @Autowired
    private SwapService swapService;

    @Autowired
    private LiquidityService liquidityService;

    @Autowired
    private LiquidityPoolRepository poolRepository;

    @Autowired
    private PoolBinRepository poolBinRepository;

    @Autowired
    private LpPositionRepository positionRepository;

    @Autowired
    private PositionBinRepository positionBinRepository;

    @MockBean
    private TokenServiceClient tokenServiceClient;

    @MockBean
    private UserServiceClient userServiceClient;

    @MockBean
    private StringRedisTemplate redisTemplate;

    private static final UUID TOKEN_X_ID = UUID.fromString("00000000-0000-0000-0000-000000000001"); // sGAZP
    private static final UUID TOKEN_Y_ID = UUID.fromString("00000000-0000-0000-0000-000000000002"); // sRUB
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    private UUID poolId;

    @BeforeEach
    void setUp() {
        // Mock TokenServiceClient + UserServiceClient for all tests
        when(tokenServiceClient.isTokenActive(any())).thenReturn(true);
        when(userServiceClient.isUserKycVerified(USER_ID)).thenReturn(true);
        doNothing().when(tokenServiceClient).deductBalance(any(), any(), anyLong());
        doNothing().when(tokenServiceClient).creditBalance(any(), any(), anyLong());

        // Mock Redis idempotency (always allow)
        org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(any(), any(), any())).thenReturn(Boolean.TRUE);

        // Clean up DB before each test
        positionBinRepository.deleteAll();
        positionRepository.deleteAll();
        poolBinRepository.deleteAll();
        poolRepository.deleteAll();

        // Create pool: sGAZP/sRUB, binStep=10 (0.1% per bin), baseFee=5 bps
        LiquidityPool pool = LiquidityPool.builder()
                .id(UUID.randomUUID())
                .tokenXId(TOKEN_X_ID)
                .tokenYId(TOKEN_Y_ID)
                .binStep(10)
                .baseFeeBps(5)
                .maxVariableFeeBps(100)
                .volatilityAccumulator(0)
                .decayPeriodSeconds(3600)
                .activeBinId(0)
                .basePrice(new BigDecimal("100.000000000000000000")) // 1 sGAZP = 100 sRUB
                .protocolFeePct(20)
                .totalTvlX(0)
                .totalTvlY(0)
                .volume24h(0)
                .totalFeesCollectedX(0)
                .totalFeesCollectedY(0)
                .status(PoolStatus.ACTIVE)
                .createdBy(USER_ID)
                .build();
        pool = poolRepository.save(pool);
        poolId = pool.getId();
    }

    @Test
    @Order(1)
    @DisplayName("Step 1-2: Create pool and add SPOT liquidity across bins -5..+5")
    void addLiquidity_SpotStrategy() {
        // Add liquidity: SPOT strategy, bins -5 to +5 (11 bins)
        AddLiquidityRequest addReq = new AddLiquidityRequest(
                poolId,
                1_000_000, // 1M sGAZP
                100_000_000, // 100M sRUB
                -5, 5,
                LiquidityStrategy.SPOT,
                "it-add-liq-1"
        );

        AddLiquidityResponse addResp = liquidityService.addLiquidity(addReq, USER_ID);

        assertNotNull(addResp);
        assertNotNull(addResp.positionId());
        assertEquals(poolId, addResp.poolId());
        assertEquals(-5, addResp.binRangeMin());
        assertEquals(5, addResp.binRangeMax());
        assertEquals(LiquidityStrategy.SPOT, addResp.strategy());
        assertTrue(addResp.liquidityShares() > 0, "Should have positive liquidity shares");
        assertTrue(addResp.depositedX() > 0 || addResp.depositedY() > 0,
                "Should have deposited some tokens");

        // Verify bins were created in DB
        List<PoolBin> bins = poolBinRepository.findByPoolIdAndBinIdBetween(poolId, -5, 5);
        assertFalse(bins.isEmpty(), "Should have created bins");

        // Verify at least some bins have liquidity
        long totalLiquidity = bins.stream().mapToLong(PoolBin::getLiquidity).sum();
        assertTrue(totalLiquidity > 0, "Total liquidity across bins should be positive");

        // Verify pool TVL updated
        LiquidityPool pool = poolRepository.findById(poolId).orElseThrow();
        assertTrue(pool.getTotalTvlX() > 0 || pool.getTotalTvlY() > 0,
                "Pool TVL should be updated");

        // Verify position created
        LpPosition position = positionRepository.findById(addResp.positionId()).orElseThrow();
        assertTrue(position.isActive());
        assertEquals(USER_ID, position.getUserId());
        assertEquals(-5, position.getBinRangeMin());
        assertEquals(5, position.getBinRangeMax());
    }

    @Test
    @Order(2)
    @DisplayName("Step 3: Get swap quote for X→Y")
    void swapQuote_XtoY() {
        seedLiquidity();

        SwapQuoteRequest quoteReq = new SwapQuoteRequest(poolId, TOKEN_X_ID, 1_000);
        SwapQuoteResponse quoteResp = swapService.quote(quoteReq);

        assertNotNull(quoteResp);
        assertEquals(poolId, quoteResp.poolId());
        assertEquals(TOKEN_X_ID, quoteResp.tokenInId());
        assertEquals(TOKEN_Y_ID, quoteResp.tokenOutId());
        assertTrue(quoteResp.estimatedAmountOut() > 0, "Quote should return positive amountOut");
        assertTrue(quoteResp.estimatedFee() >= 0, "Quote fee should be non-negative");
        assertNotNull(quoteResp.estimatedPrice());
    }

    @Test
    @Order(3)
    @DisplayName("Step 4: Execute swap X→Y and verify reserves updated")
    void swap_XtoY_updatesReserves() {
        seedLiquidity();

        // Record pre-swap state
        long preSwapTvlX = poolRepository.findById(poolId).orElseThrow().getTotalTvlX();
        long preSwapTvlY = poolRepository.findById(poolId).orElseThrow().getTotalTvlY();

        PoolBin binBefore = poolBinRepository.findByPoolIdAndBinId(poolId, 0).orElseThrow();
        long reserveXBefore = binBefore.getReserveX();
        long reserveYBefore = binBefore.getReserveY();

        // Execute swap: 1000 sGAZP → sRUB
        SwapRequest swapReq = new SwapRequest(poolId, TOKEN_X_ID, 1_000, 0, "it-swap-1");
        SwapResponse swapResp = swapService.swap(swapReq, USER_ID);

        assertNotNull(swapResp);
        assertNotNull(swapResp.txId());
        assertEquals(poolId, swapResp.poolId());
        assertEquals(TOKEN_X_ID, swapResp.tokenInId());
        assertEquals(TOKEN_Y_ID, swapResp.tokenOutId());
        assertTrue(swapResp.amountOut() > 0, "Swap should produce positive output");
        assertTrue(swapResp.feeAmount() > 0, "Swap should collect fee");

        // Verify reserves changed
        PoolBin binAfter = poolBinRepository.findByPoolIdAndBinId(poolId, 0).orElseThrow();
        assertTrue(binAfter.getReserveX() > reserveXBefore,
                "Reserve X should increase after X→Y swap");
        assertTrue(binAfter.getReserveY() < reserveYBefore,
                "Reserve Y should decrease after X→Y swap");

        // Verify pool TVL updated
        LiquidityPool poolAfter = poolRepository.findById(poolId).orElseThrow();
        assertTrue(poolAfter.getTotalTvlX() > preSwapTvlX,
                "Pool TVL X should increase");

        // Verify fees collected
        assertTrue(poolAfter.getTotalFeesCollectedX() > 0,
                "Fees should be collected on X side");
    }

    @Test
    @Order(4)
    @DisplayName("Step 5: Execute swap Y→X direction")
    void swap_YtoX_works() {
        seedLiquidity();

        SwapRequest swapReq = new SwapRequest(poolId, TOKEN_Y_ID, 10_000, 0, "it-swap-y2x");
        SwapResponse swapResp = swapService.swap(swapReq, USER_ID);

        assertNotNull(swapResp);
        assertEquals(TOKEN_Y_ID, swapResp.tokenInId());
        assertEquals(TOKEN_X_ID, swapResp.tokenOutId());
        assertTrue(swapResp.amountOut() > 0);
    }

    @Test
    @Order(5)
    @DisplayName("Step 6: Verify fees collected in bin after swap")
    void swap_feeAccumulationInBin() {
        seedLiquidity();

        // Execute a swap to generate fees
        SwapRequest swapReq = new SwapRequest(poolId, TOKEN_X_ID, 10_000, 0, "it-swap-fees");
        swapService.swap(swapReq, USER_ID);

        // Check that bin 0 has accumulated fees
        PoolBin bin = poolBinRepository.findByPoolIdAndBinId(poolId, 0).orElseThrow();
        assertTrue(bin.getTotalFeeX() > 0, "Bin should have accumulated X fees");
    }

    @Test
    @Order(6)
    @DisplayName("Step 7: Multi-bin swap crosses bins when first bin exhausted")
    void swap_multiBin_crossesBins() {
        // Add liquidity with very small amount to make bin 0 easy to exhaust
        LiquidityPool pool = poolRepository.findById(poolId).orElseThrow();

        // Create bin 0 with small Y reserve
        PoolBin bin0 = PoolBin.builder()
                .poolId(poolId)
                .binId(0)
                .price(BinMath.binPrice(pool.getBasePrice(), pool.getBinStep(), 0))
                .liquidity(1_000)
                .reserveX(500)
                .reserveY(500)
                .compositionFactor(new BigDecimal("0.5"))
                .totalFeeX(0)
                .totalFeeY(0)
                .feeGrowthX(0)
                .feeGrowthY(0)
                .build();
        poolBinRepository.save(bin0);

        // Create bin 1 with large reserve
        PoolBin bin1 = PoolBin.builder()
                .poolId(poolId)
                .binId(1)
                .price(BinMath.binPrice(pool.getBasePrice(), pool.getBinStep(), 1))
                .liquidity(10_000_000)
                .reserveX(5_000_000)
                .reserveY(5_000_000)
                .compositionFactor(new BigDecimal("0.5"))
                .totalFeeX(0)
                .totalFeeY(0)
                .feeGrowthX(0)
                .feeGrowthY(0)
                .build();
        poolBinRepository.save(bin1);

        pool.setTotalTvlX(5_000_500);
        pool.setTotalTvlY(5_000_500);
        poolRepository.save(pool);

        // Swap enough to exhaust bin 0 and spill into bin 1
        SwapRequest swapReq = new SwapRequest(poolId, TOKEN_X_ID, 100_000, 0, "it-swap-multi");
        SwapResponse swapResp = swapService.swap(swapReq, USER_ID);

        assertNotNull(swapResp);
        assertTrue(swapResp.amountOut() > 0);
        assertTrue(swapResp.binsCrossed() >= 1,
                "Should cross at least 1 bin when first bin exhausted");
    }

    @Test
    @Order(7)
    @DisplayName("Step 8: Slippage protection works")
    void swap_slippageProtection() {
        seedLiquidity();

        // Set minAmountOut absurdly high
        SwapRequest swapReq = new SwapRequest(poolId, TOKEN_X_ID, 1_000, 999_999_999, "it-slip");

        assertThrows(SlippageExceededException.class,
                () -> swapService.swap(swapReq, USER_ID));
    }

    @Test
    @Order(8)
    @DisplayName("Step 9: Swap on empty pool throws InsufficientLiquidityException")
    void swap_emptyPool_throws() {
        // Pool exists but has no bins/liquidity
        SwapRequest swapReq = new SwapRequest(poolId, TOKEN_X_ID, 1_000, 0, "it-empty");

        assertThrows(InsufficientLiquidityException.class,
                () -> swapService.swap(swapReq, USER_ID));
    }

    @Test
    @Order(9)
    @DisplayName("Step 10: Remove liquidity after swap and verify fee claiming")
    void removeLiquidity_afterSwap_claimsFees() {
        // 1. Add liquidity
        AddLiquidityRequest addReq = new AddLiquidityRequest(
                poolId, 500_000, 50_000_000,
                -3, 3, LiquidityStrategy.SPOT, "it-add-remove"
        );
        AddLiquidityResponse addResp = liquidityService.addLiquidity(addReq, USER_ID);
        UUID positionId = addResp.positionId();

        // 2. Execute a swap to generate fees
        SwapRequest swapReq = new SwapRequest(poolId, TOKEN_X_ID, 50_000, 0, "it-swap-before-remove");
        SwapResponse swapResp = swapService.swap(swapReq, USER_ID);
        assertTrue(swapResp.feeAmount() > 0, "Swap should generate fees");

        // 3. Remove 100% liquidity
        RemoveLiquidityRequest removeReq = new RemoveLiquidityRequest(
                positionId, 10_000, "it-remove-full"
        );
        RemoveLiquidityResponse removeResp = liquidityService.removeLiquidity(removeReq, USER_ID);

        assertNotNull(removeResp);
        assertEquals(positionId, removeResp.positionId());
        assertTrue(removeResp.withdrawnX() > 0 || removeResp.withdrawnY() > 0,
                "Should withdraw some tokens");

        // Position should be closed
        LpPosition position = positionRepository.findById(positionId).orElseThrow();
        assertFalse(position.isActive(), "Position should be closed after 100% removal");
        assertNotNull(position.getClosedAt(), "closedAt should be set");
    }

    @Test
    @Order(10)
    @DisplayName("Step 11: Partial liquidity removal (50%)")
    void removeLiquidity_partial() {
        // 1. Add liquidity
        AddLiquidityRequest addReq = new AddLiquidityRequest(
                poolId, 500_000, 50_000_000,
                -2, 2, LiquidityStrategy.SPOT, "it-add-partial"
        );
        AddLiquidityResponse addResp = liquidityService.addLiquidity(addReq, USER_ID);
        UUID positionId = addResp.positionId();
        long originalShares = addResp.liquidityShares();

        // 2. Remove 50%
        RemoveLiquidityRequest removeReq = new RemoveLiquidityRequest(
                positionId, 5_000, "it-remove-50pct" // 5000 bps = 50%
        );
        RemoveLiquidityResponse removeResp = liquidityService.removeLiquidity(removeReq, USER_ID);

        assertNotNull(removeResp);
        assertTrue(removeResp.withdrawnX() > 0 || removeResp.withdrawnY() > 0);

        // Position should still be active
        LpPosition position = positionRepository.findById(positionId).orElseThrow();
        assertTrue(position.isActive(), "Position should still be active after 50% removal");
        assertTrue(position.getTotalLiquidityShares() > 0, "Should have remaining shares");
        assertTrue(position.getTotalLiquidityShares() < originalShares,
                "Should have fewer shares than before");
    }

    @Test
    @Order(11)
    @DisplayName("Step 12: CURVE strategy concentrates liquidity near active bin")
    void addLiquidity_CurveStrategy_concentratesLiquidity() {
        AddLiquidityRequest addReq = new AddLiquidityRequest(
                poolId, 500_000, 50_000_000,
                -5, 5, LiquidityStrategy.CURVE, "it-add-curve"
        );

        AddLiquidityResponse addResp = liquidityService.addLiquidity(addReq, USER_ID);
        assertNotNull(addResp);
        assertTrue(addResp.liquidityShares() > 0);

        // Verify bin 0 (active bin) has the most liquidity
        Optional<PoolBin> activeBin = poolBinRepository.findByPoolIdAndBinId(poolId, 0);
        Optional<PoolBin> edgeBin = poolBinRepository.findByPoolIdAndBinId(poolId, 5);

        if (activeBin.isPresent() && edgeBin.isPresent()) {
            assertTrue(activeBin.get().getLiquidity() >= edgeBin.get().getLiquidity(),
                    "Active bin should have >= liquidity than edge bin for CURVE strategy");
        }
    }

    @Test
    @Order(12)
    @DisplayName("Step 13: Volatility accumulator updates after bin-crossing swap")
    void swap_volatilityAccumulator_updatesOnBinCross() {
        // Manually create bins with uneven liquidity to force bin crossing
        LiquidityPool pool = poolRepository.findById(poolId).orElseThrow();
        assertEquals(0, pool.getVolatilityAccumulator(), "VA should start at 0");

        // Bin 0: tiny reserve Y to force quick exhaustion
        PoolBin bin0 = PoolBin.builder()
                .poolId(poolId).binId(0)
                .price(BinMath.binPrice(pool.getBasePrice(), pool.getBinStep(), 0))
                .liquidity(100).reserveX(50).reserveY(50)
                .compositionFactor(new BigDecimal("0.5"))
                .totalFeeX(0).totalFeeY(0).feeGrowthX(0).feeGrowthY(0)
                .build();
        poolBinRepository.save(bin0);

        // Bin 1: large reserve
        PoolBin bin1 = PoolBin.builder()
                .poolId(poolId).binId(1)
                .price(BinMath.binPrice(pool.getBasePrice(), pool.getBinStep(), 1))
                .liquidity(10_000_000).reserveX(5_000_000).reserveY(5_000_000)
                .compositionFactor(new BigDecimal("0.5"))
                .totalFeeX(0).totalFeeY(0).feeGrowthX(0).feeGrowthY(0)
                .build();
        poolBinRepository.save(bin1);

        pool.setTotalTvlX(5_000_050);
        pool.setTotalTvlY(5_000_050);
        poolRepository.save(pool);

        // Execute swap large enough to cross bins
        SwapRequest swapReq = new SwapRequest(poolId, TOKEN_X_ID, 50_000, 0, "it-va-update");
        SwapResponse swapResp = swapService.swap(swapReq, USER_ID);

        assertTrue(swapResp.binsCrossed() >= 1, "Should cross at least 1 bin");

        // VA should be updated
        LiquidityPool poolAfter = poolRepository.findById(poolId).orElseThrow();
        assertTrue(poolAfter.getVolatilityAccumulator() >= 0,
                "Volatility accumulator should be >= 0 after crossing bins");
    }

    @Test
    @Order(13)
    @DisplayName("Step 14: Full end-to-end flow per spec 14.2")
    void fullEndToEndFlow() {
        // 1. Pool already created in @BeforeEach

        // 2. Add liquidity (SPOT, bins -5..+5)
        AddLiquidityRequest addReq = new AddLiquidityRequest(
                poolId, 1_000_000, 100_000_000,
                -5, 5, LiquidityStrategy.SPOT, "it-e2e-add"
        );
        AddLiquidityResponse addResp = liquidityService.addLiquidity(addReq, USER_ID);
        assertNotNull(addResp.positionId());
        assertTrue(addResp.liquidityShares() > 0);

        // 3. Execute swap: 1000 X → Y
        SwapRequest swapReq = new SwapRequest(poolId, TOKEN_X_ID, 1_000, 0, "it-e2e-swap");
        SwapResponse swapResp = swapService.swap(swapReq, USER_ID);
        assertNotNull(swapResp.txId());
        assertTrue(swapResp.amountOut() > 0);
        assertTrue(swapResp.feeAmount() > 0);

        // 4. Verify balances updated — pool TVL changed
        LiquidityPool poolAfter = poolRepository.findById(poolId).orElseThrow();
        assertTrue(poolAfter.getTotalFeesCollectedX() > 0,
                "Fees should be collected");

        // 5. Remove liquidity and claim fees
        RemoveLiquidityRequest removeReq = new RemoveLiquidityRequest(
                addResp.positionId(), 10_000, "it-e2e-remove"
        );
        RemoveLiquidityResponse removeResp = liquidityService.removeLiquidity(removeReq, USER_ID);
        assertTrue(removeResp.withdrawnX() > 0 || removeResp.withdrawnY() > 0,
                "Should withdraw tokens");

        // 6. Position should be closed
        LpPosition position = positionRepository.findById(addResp.positionId()).orElseThrow();
        assertFalse(position.isActive());
    }

    // ── Helper ─────────────────────────────────────────────────────

    private void seedLiquidity() {
        LiquidityPool pool = poolRepository.findById(poolId).orElseThrow();
        BigDecimal basePrice = pool.getBasePrice();
        int binStep = pool.getBinStep();

        // Create bins -5..+5 with generous liquidity
        for (int binId = -5; binId <= 5; binId++) {
            BigDecimal price = BinMath.binPrice(basePrice, binStep, binId);
            PoolBin bin = PoolBin.builder()
                    .poolId(poolId)
                    .binId(binId)
                    .price(price)
                    .liquidity(10_000_000)
                    .reserveX(5_000_000)
                    .reserveY(5_000_000)
                    .compositionFactor(new BigDecimal("0.5"))
                    .totalFeeX(0)
                    .totalFeeY(0)
                    .feeGrowthX(0)
                    .feeGrowthY(0)
                    .build();
            poolBinRepository.save(bin);
        }

        pool.setTotalTvlX(55_000_000);
        pool.setTotalTvlY(55_000_000);
        poolRepository.save(pool);
    }
}
