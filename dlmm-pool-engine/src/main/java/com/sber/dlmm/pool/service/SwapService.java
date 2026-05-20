package com.sber.dlmm.pool.service;

import com.sber.dlmm.common.enums.PoolStatus;
import com.sber.dlmm.common.exception.ForbiddenException;
import com.sber.dlmm.common.exception.IdempotencyConflictException;
import com.sber.dlmm.common.exception.InsufficientLiquidityException;
import com.sber.dlmm.common.exception.PoolNotActiveException;
import com.sber.dlmm.common.exception.PoolNotFoundException;
import com.sber.dlmm.common.exception.SlippageExceededException;
import com.sber.dlmm.common.util.BinMath;
import com.sber.dlmm.common.util.FeeCalculator;
import com.sber.dlmm.pool.client.TokenServiceClient;
import com.sber.dlmm.pool.client.UserServiceClient;
import com.sber.dlmm.pool.dto.SwapQuoteRequest;
import com.sber.dlmm.pool.dto.SwapQuoteResponse;
import com.sber.dlmm.pool.dto.SwapRequest;
import com.sber.dlmm.pool.dto.SwapResponse;
import com.sber.dlmm.pool.entity.LiquidityPool;
import com.sber.dlmm.pool.entity.PoolBin;
import com.sber.dlmm.pool.event.SwapExecutedEvent;
import com.sber.dlmm.pool.repository.LiquidityPoolRepository;
import com.sber.dlmm.pool.repository.PoolBinRepository;
import com.sber.dlmm.common.outbox.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.UUID;

@Service
public class SwapService {

    private static final Logger log = LoggerFactory.getLogger(SwapService.class);
    private static final String POOL_EVENTS_TOPIC = "pool-events";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final String IDEMPOTENCY_PREFIX = "idempotency:swap:";
    private static final int MAX_BIN_ITERATIONS = 1000;

    private final LiquidityPoolRepository poolRepository;
    private final PoolBinRepository poolBinRepository;
    private final TokenServiceClient tokenServiceClient;
    private final UserServiceClient userServiceClient;
    // Replaced direct KafkaTemplate with the transactional outbox — Kafka
    // send no longer happens inline with the swap mutation, which closed
    // the lost-event window. See OutboxService / OutboxDispatcher.
    private final OutboxService outbox;
    private final StringRedisTemplate redisTemplate;

    public SwapService(LiquidityPoolRepository poolRepository,
                       PoolBinRepository poolBinRepository,
                       TokenServiceClient tokenServiceClient,
                       UserServiceClient userServiceClient,
                       OutboxService outbox,
                       StringRedisTemplate redisTemplate) {
        this.poolRepository = poolRepository;
        this.poolBinRepository = poolBinRepository;
        this.tokenServiceClient = tokenServiceClient;
        this.userServiceClient = userServiceClient;
        this.outbox = outbox;
        this.redisTemplate = redisTemplate;
    }

    public SwapQuoteResponse quote(SwapQuoteRequest req) {
        LiquidityPool pool = poolRepository.findById(req.poolId())
                .orElseThrow(() -> new PoolNotFoundException("Pool not found: " + req.poolId()));

        boolean swapXtoY = req.tokenInId().equals(pool.getTokenXId());
        UUID tokenOutId = swapXtoY ? pool.getTokenYId() : pool.getTokenXId();

        // Spot price from active bin
        BigDecimal spotPrice = BinMath.binPrice(pool.getBasePrice(), pool.getBinStep(), pool.getActiveBinId());

        long remainingAmountIn = req.amountIn();
        long totalAmountOut = 0;
        long totalFee = 0;
        int binsCrossed = 0;
        int currentBinId = pool.getActiveBinId();
        int iterations = 0;

        while (remainingAmountIn > 0 && iterations < MAX_BIN_ITERATIONS) {
            iterations++;

            PoolBin bin = poolBinRepository.findByPoolIdAndBinId(pool.getId(), currentBinId).orElse(null);

            if (bin == null || bin.getLiquidity() <= 0) {
                // No liquidity in this bin, move to next
                currentBinId = swapXtoY ? currentBinId + 1 : currentBinId - 1;
                binsCrossed++;
                continue;
            }

            BigDecimal binPrice = bin.getPrice();
            if (binPrice == null || binPrice.compareTo(BigDecimal.ZERO) <= 0) {
                binPrice = BinMath.binPrice(pool.getBasePrice(), pool.getBinStep(), currentBinId);
            }

            // Calculate max amount of input token this bin can absorb
            long maxAmountIn;
            if (swapXtoY) {
                // Swapping X for Y: bin gives out Y, limited by bin.reserveY
                // maxAmountIn (in X) = bin.reserveY / price
                maxAmountIn = BigDecimal.valueOf(bin.getReserveY())
                        .divide(binPrice, 0, RoundingMode.FLOOR).longValue();
            } else {
                // Swapping Y for X: bin gives out X, limited by bin.reserveX
                // maxAmountIn (in Y) = bin.reserveX * price
                maxAmountIn = BigDecimal.valueOf(bin.getReserveX())
                        .multiply(binPrice, MC)
                        .setScale(0, RoundingMode.FLOOR).longValue();
            }

            if (maxAmountIn <= 0) {
                currentBinId = swapXtoY ? currentBinId + 1 : currentBinId - 1;
                binsCrossed++;
                continue;
            }

            long actualAmountIn = Math.min(remainingAmountIn, maxAmountIn);

            // Calculate fee
            long fee = FeeCalculator.calculateSwapFee(actualAmountIn, pool.getBaseFeeBps(),
                    pool.getVolatilityAccumulator(), pool.getBinStep());
            long netAmountIn = actualAmountIn - fee;

            // Calculate output
            long amountOut;
            if (swapXtoY) {
                // amountOut (Y) = netAmountIn * price
                amountOut = BigDecimal.valueOf(netAmountIn)
                        .multiply(binPrice, MC)
                        .setScale(0, RoundingMode.FLOOR).longValue();
            } else {
                // amountOut (X) = netAmountIn / price
                amountOut = BigDecimal.valueOf(netAmountIn)
                        .divide(binPrice, 0, RoundingMode.FLOOR).longValue();
            }

            totalAmountOut += amountOut;
            totalFee += fee;
            remainingAmountIn -= actualAmountIn;

            // If bin is exhausted, move to next
            if (actualAmountIn >= maxAmountIn) {
                currentBinId = swapXtoY ? currentBinId + 1 : currentBinId - 1;
                binsCrossed++;
            }
        }

        if (totalAmountOut <= 0) {
            throw new InsufficientLiquidityException("Insufficient liquidity for swap");
        }

        long consumedAmountIn = req.amountIn() - remainingAmountIn;
        BigDecimal executionPrice = consumedAmountIn > 0
                ? BigDecimal.valueOf(totalAmountOut).divide(BigDecimal.valueOf(consumedAmountIn), 18, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // Price impact = |executionPrice - spotPrice| / spotPrice * 100
        BigDecimal priceImpact = BigDecimal.ZERO;
        if (spotPrice.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal diff = executionPrice.subtract(spotPrice).abs();
            priceImpact = diff.divide(spotPrice, 18, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(4, RoundingMode.HALF_UP);
        }

        int estimatedFeeBps = consumedAmountIn > 0
                ? (int) (totalFee * 10_000 / consumedAmountIn)
                : 0;

        return new SwapQuoteResponse(pool.getId(), req.tokenInId(), tokenOutId,
                consumedAmountIn, totalAmountOut, totalFee, estimatedFeeBps,
                binsCrossed, executionPrice, priceImpact);
    }

    // Sprint 4 #4.7 — same-pool row lock fix via optimistic locking.
    // Public swap() wraps the @Transactional swapTransactional() in a
    // bounded retry loop. When two concurrent swaps hit the same pool
    // and both bump LiquidityPool.@Version, JPA throws
    // ObjectOptimisticLockingFailureException — we catch and retry
    // with a tiny jittered backoff. Failure is rare under realistic
    // multi-pool load; the loop is the safety net for hot pools.
    //
    // Self-invocation via appCtx.getBean() so the proxy intercept fires
    // each retry (this.swapTransactional() would bypass @Transactional).
    private static final int MAX_SWAP_ATTEMPTS = 5;
    private static final long RETRY_BASE_BACKOFF_MS = 5L;

    @Autowired
    private ApplicationContext appCtx;

    public SwapResponse swap(SwapRequest req, UUID userId) {
        // Idempotency must run ONCE per request, not per retry —
        // otherwise the second attempt sees its own "processing"
        // marker and throws IdempotencyConflictException.
        if (req.idempotencyKey() != null && !req.idempotencyKey().isBlank()) {
            String redisKey = IDEMPOTENCY_PREFIX + req.idempotencyKey();
            Boolean wasAbsent = redisTemplate.opsForValue()
                    .setIfAbsent(redisKey, "processing", Duration.ofHours(24));
            if (Boolean.FALSE.equals(wasAbsent)) {
                throw new IdempotencyConflictException("Duplicate swap request: " + req.idempotencyKey());
            }
        }

        SwapService self = appCtx.getBean(SwapService.class);
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return self.swapTransactional(req, userId);
            } catch (ObjectOptimisticLockingFailureException ex) {
                if (attempt >= MAX_SWAP_ATTEMPTS) {
                    log.warn("Swap failed after {} retries on pool {} due to optimistic lock contention",
                            attempt, req.poolId());
                    throw ex;
                }
                // Tiny jittered backoff: 5–25 ms × attempt. Prevents
                // synchronised retry storms on the same hot pool.
                long backoff = RETRY_BASE_BACKOFF_MS * attempt
                        + (long) (Math.random() * RETRY_BASE_BACKOFF_MS * attempt);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
                log.debug("Swap retry {}/{} on pool {} after {}ms backoff",
                        attempt, MAX_SWAP_ATTEMPTS, req.poolId(), backoff);
            }
        }
    }

    @Transactional
    public SwapResponse swapTransactional(SwapRequest req, UUID userId) {
        // 1. Validate
        LiquidityPool pool = poolRepository.findById(req.poolId())
                .orElseThrow(() -> new PoolNotFoundException("Pool not found: " + req.poolId()));

        if (pool.getStatus() != PoolStatus.ACTIVE) {
            throw new PoolNotActiveException("Pool is not active: " + pool.getStatus());
        }

        if (!tokenServiceClient.isTokenActive(req.tokenInId())) {
            throw new PoolNotActiveException("Input token is not active");
        }

        if (!userServiceClient.isUserKycVerified(userId)) {
            throw new ForbiddenException("User KYC not verified");
        }

        // Sprint 6 #6.7 — 115-ФЗ самозапрет gate. Swap = new-money-out path,
        // blocked when restriction is active. Existing positions remain
        // operable (remove-liquidity / claim-fee path is NOT gated).
        if (userServiceClient.isUserSelfRestricted(userId)) {
            throw new com.sber.dlmm.common.exception.UserSelfRestrictedException(
                    "Установлен самозапрет (115-ФЗ). Новые позиции запрещены. " +
                    "Запросите снятие через /профиль (период охлаждения 7 дней).");
        }

        // Idempotency check moved to the outer swap() — see comment there.

        boolean swapXtoY = req.tokenInId().equals(pool.getTokenXId());
        UUID tokenOutId = swapXtoY ? pool.getTokenYId() : pool.getTokenXId();

        // Sprint 4 #4.2 — counterparty single-swap exposure cap.
        // Enforce per-pool cap on the input side. NULL = no limit (default
        // for existing pools, backward-compatible). For corp FX hedge pool
        // SRUB/SCNY, admin will set max_single_swap_nominal_y to 50M SRUB.
        Long cap = swapXtoY ? pool.getMaxSingleSwapNominalX() : pool.getMaxSingleSwapNominalY();
        if (cap != null && req.amountIn() > cap) {
            throw new com.sber.dlmm.common.exception.CounterpartyLimitExceededException(
                    "Single-swap exposure cap exceeded: amount " + req.amountIn()
                    + " > cap " + cap + " on pool " + pool.getId());
        }

        BigDecimal spotPrice = BinMath.binPrice(pool.getBasePrice(), pool.getBinStep(), pool.getActiveBinId());

        // 2. Execute swap atomically, updating bins
        long remainingAmountIn = req.amountIn();
        long totalAmountOut = 0;
        long totalFee = 0;
        int binsCrossed = 0;
        int currentBinId = pool.getActiveBinId();
        int lastActiveBinId = currentBinId;
        int iterations = 0;

        while (remainingAmountIn > 0 && iterations < MAX_BIN_ITERATIONS) {
            iterations++;

            PoolBin bin = poolBinRepository.findByPoolIdAndBinId(pool.getId(), currentBinId).orElse(null);

            if (bin == null || bin.getLiquidity() <= 0) {
                currentBinId = swapXtoY ? currentBinId + 1 : currentBinId - 1;
                binsCrossed++;
                continue;
            }

            BigDecimal binPrice = bin.getPrice();
            if (binPrice == null || binPrice.compareTo(BigDecimal.ZERO) <= 0) {
                binPrice = BinMath.binPrice(pool.getBasePrice(), pool.getBinStep(), currentBinId);
            }

            // Max input this bin can absorb
            long maxAmountIn;
            if (swapXtoY) {
                maxAmountIn = BigDecimal.valueOf(bin.getReserveY())
                        .divide(binPrice, 0, RoundingMode.FLOOR).longValue();
            } else {
                maxAmountIn = BigDecimal.valueOf(bin.getReserveX())
                        .multiply(binPrice, MC)
                        .setScale(0, RoundingMode.FLOOR).longValue();
            }

            if (maxAmountIn <= 0) {
                currentBinId = swapXtoY ? currentBinId + 1 : currentBinId - 1;
                binsCrossed++;
                continue;
            }

            long actualAmountIn = Math.min(remainingAmountIn, maxAmountIn);

            long fee = FeeCalculator.calculateSwapFee(actualAmountIn, pool.getBaseFeeBps(),
                    pool.getVolatilityAccumulator(), pool.getBinStep());
            long netAmountIn = actualAmountIn - fee;

            long amountOut;
            if (swapXtoY) {
                amountOut = BigDecimal.valueOf(netAmountIn)
                        .multiply(binPrice, MC)
                        .setScale(0, RoundingMode.FLOOR).longValue();
            } else {
                amountOut = BigDecimal.valueOf(netAmountIn)
                        .divide(binPrice, 0, RoundingMode.FLOOR).longValue();
            }

            // 2a. Atomically update bin reserves
            if (swapXtoY) {
                bin.setReserveX(bin.getReserveX() + netAmountIn);
                bin.setReserveY(bin.getReserveY() - amountOut);
            } else {
                bin.setReserveX(bin.getReserveX() - amountOut);
                bin.setReserveY(bin.getReserveY() + netAmountIn);
            }

            // Update composition factor
            bin.setCompositionFactor(BinMath.compositionFactor(bin.getReserveY(), bin.getLiquidity()));

            // Fee distribution: protocol vs LP
            long protocolFee = fee * pool.getProtocolFeePct() / 100;
            long lpFee = fee - protocolFee;

            // Update feeGrowth for LP fee distribution
            if (bin.getLiquidity() > 0) {
                if (swapXtoY) {
                    bin.setFeeGrowthX(bin.getFeeGrowthX() + lpFee / bin.getLiquidity());
                    bin.setTotalFeeX(bin.getTotalFeeX() + fee);
                } else {
                    bin.setFeeGrowthY(bin.getFeeGrowthY() + lpFee / bin.getLiquidity());
                    bin.setTotalFeeY(bin.getTotalFeeY() + fee);
                }
            }

            poolBinRepository.save(bin);

            // Accumulate pool-level fee stats.
            // Sprint 6 #3.2 — protocol-side split. Pre-#3.2 the WHOLE fee
            // was lumped into totalFeesCollected — conflating LP and protocol
            // for reporting. Now we accumulate the protocol slice separately
            // so the treasury sweep (Sprint 7+) has an unambiguous source.
            // Note: totalFeesCollected still tracks GROSS fee (LP + protocol)
            // so existing dashboards keep their meaning; protocol slice is
            // additive metadata.
            if (swapXtoY) {
                pool.setTotalFeesCollectedX(pool.getTotalFeesCollectedX() + fee);
                pool.setTotalProtocolFeeX(pool.getTotalProtocolFeeX() + protocolFee);
            } else {
                pool.setTotalFeesCollectedY(pool.getTotalFeesCollectedY() + fee);
                pool.setTotalProtocolFeeY(pool.getTotalProtocolFeeY() + protocolFee);
            }

            totalAmountOut += amountOut;
            totalFee += fee;
            remainingAmountIn -= actualAmountIn;

            // Track last bin with liquidity
            lastActiveBinId = currentBinId;

            if (actualAmountIn >= maxAmountIn) {
                currentBinId = swapXtoY ? currentBinId + 1 : currentBinId - 1;
                binsCrossed++;
            }
        }

        if (totalAmountOut <= 0) {
            throw new InsufficientLiquidityException("Insufficient liquidity for swap");
        }

        long consumedAmountIn = req.amountIn() - remainingAmountIn;

        // 7. Slippage check BEFORE deducting (using computed amountOut)
        if (req.minAmountOut() > 0 && totalAmountOut < req.minAmountOut()) {
            throw new SlippageExceededException(
                    "Slippage exceeded: expected min " + req.minAmountOut() + " but got " + totalAmountOut);
        }

        // 3. Update activeBinId
        pool.setActiveBinId(lastActiveBinId);

        // 4. Update volatility accumulator
        int maxVolatility = pool.getMaxVariableFeeBps() * 100;
        pool.setVolatilityAccumulator(
                FeeCalculator.updateVolatilityAccumulator(pool.getVolatilityAccumulator(), binsCrossed, maxVolatility));

        // Update pool TVL
        if (swapXtoY) {
            pool.setTotalTvlX(pool.getTotalTvlX() + consumedAmountIn);
            pool.setTotalTvlY(Math.max(0, pool.getTotalTvlY() - totalAmountOut));
        } else {
            pool.setTotalTvlY(pool.getTotalTvlY() + consumedAmountIn);
            pool.setTotalTvlX(Math.max(0, pool.getTotalTvlX() - totalAmountOut));
        }

        poolRepository.save(pool);

        // 5. Deduct input token from user
        tokenServiceClient.deductBalance(userId, req.tokenInId(), consumedAmountIn);

        // 6. Credit output token to user
        tokenServiceClient.creditBalance(userId, tokenOutId, totalAmountOut);

        // Execution price and price impact
        BigDecimal executionPrice = consumedAmountIn > 0
                ? BigDecimal.valueOf(totalAmountOut)
                        .divide(BigDecimal.valueOf(consumedAmountIn), 18, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal priceImpact = BigDecimal.ZERO;
        if (spotPrice.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal diff = executionPrice.subtract(spotPrice).abs();
            priceImpact = diff.divide(spotPrice, 18, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(4, RoundingMode.HALF_UP);
        }

        int feeBps = consumedAmountIn > 0
                ? (int) (totalFee * 10_000 / consumedAmountIn)
                : 0;

        UUID txId = UUID.randomUUID();

        // 9. Outbox: durable swap-executed event. Same DB transaction as the
        // pool/bin updates and the inter-service deduct/credit calls, so
        // either everything sticks and Kafka eventually sees the event, or
        // nothing sticks and nothing gets emitted. No half-applied swaps.
        // Sprint 9-DS-r2: event now carries txId + tokenOutId +
        // idempotencyKey so transaction-service can persist a row in the
        // transactions table — was previously missing, leaving every
        // live swap balance-changing but /transactions/me empty.
        outbox.append("pool", pool.getId().toString(), "SwapExecuted", POOL_EVENTS_TOPIC,
                new SwapExecutedEvent(txId, pool.getId(), userId,
                        req.tokenInId(), tokenOutId,
                        consumedAmountIn, totalAmountOut, totalFee, binsCrossed,
                        req.idempotencyKey()));

        log.info("Swap executed: pool={}, user={}, tx={}, in={} {}, out={}, fee={}, bins={}",
                pool.getId(), userId, txId, consumedAmountIn,
                swapXtoY ? "X→Y" : "Y→X", totalAmountOut, totalFee, binsCrossed);

        // 10. Return SwapResponse
        return new SwapResponse(txId, pool.getId(), req.tokenInId(), tokenOutId,
                consumedAmountIn, totalAmountOut, totalFee, feeBps,
                binsCrossed, executionPrice, priceImpact);
    }
}
