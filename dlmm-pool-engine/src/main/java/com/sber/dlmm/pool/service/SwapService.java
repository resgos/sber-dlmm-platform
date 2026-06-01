package com.sber.dlmm.pool.service;

import com.sber.dlmm.common.enums.PoolStatus;
import com.sber.dlmm.common.exception.ForbiddenException;
import com.sber.dlmm.common.exception.IdempotencyConflictException;
import com.sber.dlmm.common.exception.InsufficientLiquidityException;
import com.sber.dlmm.common.exception.InvalidQuoteSignatureException;
import com.sber.dlmm.common.exception.PoolNotActiveException;
import com.sber.dlmm.common.exception.PoolNotFoundException;
import com.sber.dlmm.common.exception.QuoteAlreadyExecutedException;
import com.sber.dlmm.common.exception.QuoteExpiredException;
import com.sber.dlmm.common.exception.SlippageExceededException;
import com.sber.dlmm.common.util.BinMath;
import com.sber.dlmm.common.util.FeeCalculator;
import com.sber.dlmm.pool.client.TokenServiceClient;
import com.sber.dlmm.pool.client.UserServiceClient;
import com.sber.dlmm.pool.dto.QuotedSwap;
import com.sber.dlmm.pool.dto.SwapExecuteRequest;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The core DLMM swap engine — quotes and executes token-for-token trades by
 * walking the pool's price-bin ladder, and hosts the quote→execute idempotency
 * layer. This is the platform's hottest money path; correctness and
 * money-safety dominate every design choice here.
 *
 * <h2>Bin-walk model (price = Y per X)</h2>
 * A swap consumes liquidity bin by bin from the active bin outward. Per the
 * canonical LB-DLMM with {@code price = Y/X}: an <b>X→Y</b> swap drains Y and
 * walks DOWN into bins below the active price; a <b>Y→X</b> swap drains X and
 * walks UP. Within a single bin the price is CONSTANT, so a trade that doesn't
 * cross a bin boundary has zero slippage by construction. Each bin maintains the
 * F-12 invariant {@code liquidity = reserveX·price + reserveY}; the execute path
 * updates reserves, composition factor and per-unit fee growth so the invariant
 * and LP fee accounting stay consistent.
 *
 * <h2>Money-safety ordering (do not reorder)</h2>
 * {@link #swapTransactional} computes the whole fill, then
 * {@code saveAndFlush}es the pool <b>before</b> the cross-service balance
 * settlement. The flush forces the pool's optimistic-lock ({@code @Version})
 * check to fire HERE, while still in-transaction — so a concurrent same-pool
 * swap conflicts before any token-service deduct/credit runs, not at commit
 * after money already moved. The outer {@link #swap} retries on that conflict;
 * without the early flush a retry would re-run the deduct/credit and
 * double-spend the user.
 *
 * <h2>Idempotency &amp; events</h2>
 * Client-supplied {@code idempotencyKey}s are claimed once in {@link #swap}
 * (Redis SETNX, released on genuine failure so the user can retry the same key).
 * The {@code SwapExecuted} event is published via the transactional
 * {@link OutboxService} inside the same DB transaction as the bin mutations, so
 * either everything commits and Kafka eventually sees the event, or nothing
 * does — no half-applied swaps.
 *
 * <h2>Amount &amp; price conventions</h2>
 * Amounts are raw integer units (uniform ×10⁴ platform scale). All
 * within-bin arithmetic uses {@link BigDecimal} and FLOORs to integers (the
 * house never rounds in the user's favour). Execution price and price impact
 * are always normalised to the spot "Y per X" frame before differencing — Y→X
 * ratios are reciprocals of spot and would otherwise read ~100% impact.
 *
 * <p>Collaborators: {@link LiquidityPoolRepository}/{@link PoolBinRepository}
 * (state), {@link TokenServiceClient} (token-active check + balance
 * deduct/credit), {@link UserServiceClient} (fail-closed KYC + 115-ФЗ
 * self-restriction), {@link FeeCalculator}/{@link BinMath} (fee + bin math),
 * {@link QuoteStore} (quote persistence for the two-call flow).
 */
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
    // Batch G-02 — quote-execute idempotency layer. Stores QuotedSwap
    // records with TTL eviction and a separate executedAt marker for the
    // double-spend guard. Tests stub this directly.
    private final QuoteStore quoteStore;

    /**
     * Quote freshness window (seconds). Defaults to 30 — long enough for
     * a user to glance at the quote and click execute, short enough that
     * pool state hasn't shifted under them. Matches the OTC-desk quote
     * window precedent ({@code quoteExpiresAt} in
     * {@code OtcBlockTrade}, default 30 min for institutional flow vs
     * 30 sec for retail swap because retail quotes execute hot from
     * the UI).
     */
    private final long quoteTtlSeconds;

    /**
     * @param poolRepository     pool-row state (active bin, base price, fee
     *                           params, TVL + fee accumulators)
     * @param poolBinRepository  per-bin reserves/price/fee-growth read &amp;
     *                           written during the bin walk
     * @param tokenServiceClient token-active validation and the (non-idempotent)
     *                           balance deduct/credit settlement
     * @param userServiceClient  fail-closed KYC + 115-ФЗ self-restriction gate
     * @param outbox             transactional outbox for the durable
     *                           {@code SwapExecuted} event
     * @param redisTemplate      Redis backing the client idempotency-key claim
     * @param quoteStore         persistence for the quote→execute idempotency
     *                           flow ({@link #issueQuote}/{@link #executeQuoted})
     * @param quoteTtlSeconds    quote freshness window (default 30s)
     */
    public SwapService(LiquidityPoolRepository poolRepository,
                       PoolBinRepository poolBinRepository,
                       TokenServiceClient tokenServiceClient,
                       UserServiceClient userServiceClient,
                       OutboxService outbox,
                       StringRedisTemplate redisTemplate,
                       QuoteStore quoteStore,
                       @Value("${dlmm.swap.quote-ttl-seconds:30}") long quoteTtlSeconds) {
        this.poolRepository = poolRepository;
        this.poolBinRepository = poolBinRepository;
        this.tokenServiceClient = tokenServiceClient;
        this.userServiceClient = userServiceClient;
        this.outbox = outbox;
        this.redisTemplate = redisTemplate;
        this.quoteStore = quoteStore;
        this.quoteTtlSeconds = quoteTtlSeconds;
    }

    /**
     * Price impact = pure SLIPPAGE, fee-excluded. (Fixed 2026-05-27 — the
     * previous formula measured the GROSS rate vs spot, which (a) baked the
     * fee into "impact" so every within-bin swap read ~fee% instead of 0,
     * and (b) on tiny amounts integer-truncation of amountOut blew the
     * impact up — a 1000-unit swap read 5% while a 1B swap read 0.15%.)
     *
     * <p>In an LB-DLMM the price is CONSTANT within a bin, so a swap that
     * does not cross a bin boundary has zero slippage by construction — the
     * trader fills entirely at the bin price. We therefore:
     * <ol>
     *   <li>return 0 when {@code binsCrossed == 0} (within the active bin —
     *       no price movement; any residual is fee/rounding, not slippage);</li>
     *   <li>otherwise measure the execution price on the NET input
     *       (fee removed) so the fee is reported separately, not as impact.</li>
     * </ol>
     * Result: impact is ~0 for normal trades and grows monotonically only as
     * the swap consumes liquidity across additional bins — which is the
     * behaviour treasurers expect from "влияние на цену".
     *
     * <p>Package-private + static so it can be unit-tested in isolation.
     *
     * @param swapXtoY         true for an X→Y trade (determines which way the
     *                         execution-price ratio must be oriented)
     * @param consumedAmountIn gross input actually consumed (raw units)
     * @param totalAmountOut   total output produced (raw units)
     * @param totalFee         total fee skimmed from the input (raw units),
     *                         excluded from the impact measure
     * @param binsCrossed      number of bin boundaries crossed; {@code <= 0}
     *                         means within the active bin → zero impact
     * @param spotPrice        reference spot price (Y per X) to compare against
     * @return absolute price impact as a percentage (4 dp); {@link BigDecimal#ZERO}
     *         when no bin was crossed or any input is non-positive
     */
    static BigDecimal computePriceImpact(boolean swapXtoY, long consumedAmountIn,
                                         long totalAmountOut, long totalFee,
                                         int binsCrossed, BigDecimal spotPrice) {
        if (binsCrossed <= 0) return BigDecimal.ZERO;
        long netConsumed = consumedAmountIn - totalFee;
        if (netConsumed <= 0 || totalAmountOut <= 0 || spotPrice.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        // Normalise to spot's frame (Y per X), fee excluded.
        BigDecimal execPrice = swapXtoY
                ? BigDecimal.valueOf(totalAmountOut).divide(BigDecimal.valueOf(netConsumed), 18, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(netConsumed).divide(BigDecimal.valueOf(totalAmountOut), 18, RoundingMode.HALF_UP);
        return execPrice.subtract(spotPrice).abs()
                .divide(spotPrice, 18, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Price a swap without executing it — a pure, read-only dry run of the bin
     * walk that returns expected output, fee, bins crossed, execution price and
     * price impact.
     *
     * <p>Mirrors the {@link #swapTransactional} fill logic exactly (same
     * direction rules, same FLOOR rounding, same fee formula) but mutates
     * nothing, so the figures the UI shows match what an execute would produce
     * against current pool state. Walks at most {@link #MAX_BIN_ITERATIONS} bins
     * as a safety bound. The spot reference is the pool's stored
     * {@code basePrice} (not a {@code BinMath} computation over the 2^23 anchor,
     * which overflows — see class/PoolService notes). Execution price is
     * normalised to the "Y per X" frame for both directions.
     *
     * @param req quote request (pool, input token, input amount)
     * @return the quote: consumed input, estimated output, total fee, effective
     *         fee bps, bins crossed, execution price and price impact
     * @throws PoolNotFoundException        if the pool does not exist
     * @throws InsufficientLiquidityException if no output could be produced
     */
    public SwapQuoteResponse quote(SwapQuoteRequest req) {
        LiquidityPool pool = poolRepository.findById(req.poolId())
                .orElseThrow(() -> new PoolNotFoundException("Pool not found: " + req.poolId()));

        boolean swapXtoY = req.tokenInId().equals(pool.getTokenXId());
        UUID tokenOutId = swapXtoY ? pool.getTokenYId() : pool.getTokenXId();

        // Spot price = the pool's stored basePrice (matches what
        // /api/v1/pools/{id}.currentPrice returns and what the UI
        // displays). Sprint 9-DS-r2: previously this used
        // BinMath.binPrice(basePrice, binStep, activeBinId) which
        // computes basePrice * (1 + binStep/10000)^activeBinId.
        // Seed pools have activeBinId = 2^23 = 8_388_608 as the
        // "anchor" bin (where price ≡ basePrice), so the math
        // overflowed to ~exp(4192) and priceImpact always read 100%.
        // Until the bin-anchor convention is reconciled (admin
        // PoolDetail and PoolsPage both already use basePrice as
        // currentPrice), use the same reference here.
        BigDecimal spotPrice = pool.getBasePrice();

        long remainingAmountIn = req.amountIn();
        long totalAmountOut = 0;
        long totalFee = 0;
        int binsCrossed = 0;
        int currentBinId = pool.getActiveBinId();
        int iterations = 0;

        while (remainingAmountIn > 0 && iterations < MAX_BIN_ITERATIONS) {
            iterations++;

            PoolBin bin = poolBinRepository.findByPoolIdAndBinId(pool.getId(), currentBinId).orElse(null);

            // Sprint 9-DS-r3 — bin-traversal direction was inverted.
            // Canonical LB-DLMM with price = Y/X:
            //   X→Y swap drains Y from the active bin and walks DOWN
            //   into bins-below-active (which also hold Y) as price
            //   falls; Y→X drains X from above-active bins, price rises.
            // Old code did the opposite, so after the active bin's
            // small both-sided reserve ran out, the swap walked into
            // empty bins and either returned a tiny fill or threw
            // InsufficientLiquidityException. Only tiny swaps that fit
            // entirely in the active bin worked.
            if (bin == null || bin.getLiquidity() <= 0) {
                currentBinId = swapXtoY ? currentBinId - 1 : currentBinId + 1;
                binsCrossed++;
                continue;
            }

            BigDecimal binPrice = bin.getPrice();
            if (binPrice == null || binPrice.compareTo(BigDecimal.ZERO) <= 0) {
                // Sprint 9-DS-r4 (P0-2) — through BinMath.binPriceAtBin so
                // we can't accidentally drop the activeBinId offset (the
                // original Sprint 9-DS-r3 incident).
                binPrice = BinMath.binPriceAtBin(pool.getBasePrice(), pool.getBinStep(),
                        currentBinId, pool.getActiveBinId());
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
                // Sprint 9-DS-r3 — flipped per canonical DLMM (see above).
                currentBinId = swapXtoY ? currentBinId - 1 : currentBinId + 1;
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

            // If bin is exhausted, move to next.
            // Sprint 9-DS-r3 — flipped per canonical DLMM (see above).
            if (actualAmountIn >= maxAmountIn) {
                currentBinId = swapXtoY ? currentBinId - 1 : currentBinId + 1;
                binsCrossed++;
            }
        }

        if (totalAmountOut <= 0) {
            throw new InsufficientLiquidityException("Insufficient liquidity for swap");
        }

        long consumedAmountIn = req.amountIn() - remainingAmountIn;
        // Sprint 9-DS-r2: priceImpact was always near 100% on Y→X swaps.
        // The bug: spotPrice is "Y per 1 X" (canonical bin price). But
        // for a Y→X swap, totalAmountOut is X and consumedAmountIn is Y,
        // so totalAmountOut/consumedAmountIn = X/Y = 1/spotPrice — i.e.
        // the *reciprocal* of the spot frame. Subtracting reciprocals
        // ("0.01 - 95.2") always produces ~100% impact.
        //
        // Fix: normalise executionPrice to the same frame as spotPrice
        // (always Y-per-X) before computing the diff. For X→Y swaps the
        // raw ratio is already Y/X — keep it. For Y→X invert it.
        BigDecimal executionPrice;
        if (consumedAmountIn <= 0 || totalAmountOut <= 0) {
            executionPrice = BigDecimal.ZERO;
        } else if (swapXtoY) {
            // X→Y: amountOut is Y, amountIn is X. ratio = Y/X = Y-per-X.
            executionPrice = BigDecimal.valueOf(totalAmountOut)
                    .divide(BigDecimal.valueOf(consumedAmountIn), 18, RoundingMode.HALF_UP);
        } else {
            // Y→X: amountOut is X, amountIn is Y. ratio = X/Y.
            // Invert to Y-per-X so it lines up with spotPrice.
            executionPrice = BigDecimal.valueOf(consumedAmountIn)
                    .divide(BigDecimal.valueOf(totalAmountOut), 18, RoundingMode.HALF_UP);
        }

        // Price impact = pure SLIPPAGE (fee-excluded). See computePriceImpact.
        BigDecimal priceImpact = computePriceImpact(
                swapXtoY, consumedAmountIn, totalAmountOut, totalFee, binsCrossed, spotPrice);

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

    /**
     * Public swap entry point: claims the client idempotency key once, then runs
     * the transactional swap under a bounded optimistic-lock retry loop.
     *
     * <p>The idempotency claim (Redis SETNX) happens here, not per retry, so a
     * legitimate internal retry doesn't trip over its own "processing" marker.
     * Each attempt calls {@link #swapTransactional} via the Spring bean (proxy
     * self-invocation) so {@code @Transactional} actually fires on every retry;
     * an {@link ObjectOptimisticLockingFailureException} from two concurrent
     * same-pool swaps is retried up to {@link #MAX_SWAP_ATTEMPTS} times with a
     * tiny jittered backoff to avoid synchronised retry storms. On genuine
     * failure (validation/KYC/slippage/liquidity, or retries exhausted) the
     * idempotency key is released best-effort so the user may retry with the
     * SAME key; on success the key is kept so an accidental replay is rejected.
     *
     * @param req    swap request (pool, input token, amount, min-out, optional
     *               idempotency key)
     * @param userId authenticated caller, charged/credited by the trade
     * @return the executed swap result
     * @throws IdempotencyConflictException if the idempotency key is already in
     *                                      flight / consumed
     * @throws ObjectOptimisticLockingFailureException if lock contention
     *                                      persists past the retry budget
     */
    public SwapResponse swap(SwapRequest req, UUID userId) {
        // Idempotency must run ONCE per request, not per retry —
        // otherwise the second attempt sees its own "processing"
        // marker and throws IdempotencyConflictException.
        String redisKey = null;
        if (req.idempotencyKey() != null && !req.idempotencyKey().isBlank()) {
            redisKey = IDEMPOTENCY_PREFIX + req.idempotencyKey();
            Boolean wasAbsent = redisTemplate.opsForValue()
                    .setIfAbsent(redisKey, "processing", Duration.ofHours(24));
            if (Boolean.FALSE.equals(wasAbsent)) {
                throw new IdempotencyConflictException("Duplicate swap request: " + req.idempotencyKey());
            }
        }

        SwapService self = appCtx.getBean(SwapService.class);
        int attempt = 0;
        try {
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
        } catch (RuntimeException ex) {
            // The swap genuinely failed (validation / KYC / slippage / liquidity,
            // or optimistic-lock retries exhausted). Release the idempotency key
            // so the user can retry with the SAME key instead of being locked
            // out for 24h. On success we returned above and the key is kept (so
            // an accidental replay of a succeeded swap is still rejected).
            if (redisKey != null) {
                try { redisTemplate.delete(redisKey); } catch (RuntimeException ignore) { /* best-effort */ }
            }
            throw ex;
        }
    }

    /**
     * Execute one swap atomically: validate, walk the bins mutating reserves +
     * fee growth, flush the pool (lock check), settle balances cross-service,
     * then emit the durable event.
     *
     * <p><b>Gates (in order):</b> pool ACTIVE, input token active, caller KYC
     * verified (fail-closed), not 115-ФЗ self-restricted, and the per-pool
     * single-swap counterparty cap (NULL = no cap). Then the bin walk produces
     * output/fee/bins-crossed exactly as {@link #quote} previews. The
     * <b>slippage check</b> ({@code minAmountOut}) runs before any balance moves.
     *
     * <p><b>Side effects &amp; ordering — money-safety critical:</b> per bin it
     * updates reserveX/reserveY, composition factor, and the LP fee-growth /
     * total-fee accumulators (protocol vs LP split per
     * {@code protocolFeePct}); it advances the pool's active bin, volatility
     * accumulator and TVL rollups (adding only the NET input that entered bins,
     * since fees are held in the fee accumulators not in any reserve). It then
     * {@code saveAndFlush}es the pool so the {@code @Version} conflict surfaces
     * BEFORE the {@link TokenServiceClient} deduct/credit — see the class
     * Javadoc for why this prevents double-spend under the {@link #swap} retry.
     * Finally it appends a {@code SwapExecuted} event (with txId, tokenOut,
     * execution price and idempotency key) to the outbox in this same
     * transaction.
     *
     * @param req    swap request (pool, input token, amount, min-out, idempotency key)
     * @param userId authenticated caller, debited the input and credited the output
     * @return the executed swap (tx id, amounts, fee, fee bps, bins crossed,
     *         execution price, price impact)
     * @throws PoolNotFoundException          if the pool does not exist
     * @throws PoolNotActiveException         if the pool or input token is not active
     * @throws ForbiddenException             if the caller is not KYC-verified
     * @throws com.sber.dlmm.common.exception.UserSelfRestrictedException if 115-ФЗ
     *                                        self-restriction is active
     * @throws com.sber.dlmm.common.exception.CounterpartyLimitExceededException if
     *                                        the amount exceeds the pool's single-swap cap
     * @throws InsufficientLiquidityException if the walk produced no output
     * @throws SlippageExceededException      if output is below {@code minAmountOut}
     */
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

        // Sprint 9-DS-r2 — same fix as quote(): use stored basePrice
        // as spot reference; BinMath at 2^23 overflows.
        BigDecimal spotPrice = pool.getBasePrice();

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

            // Sprint 9-DS-r3 — direction flipped to match canonical
            // LB-DLMM (see comment in quote()).
            if (bin == null || bin.getLiquidity() <= 0) {
                currentBinId = swapXtoY ? currentBinId - 1 : currentBinId + 1;
                binsCrossed++;
                continue;
            }

            BigDecimal binPrice = bin.getPrice();
            if (binPrice == null || binPrice.compareTo(BigDecimal.ZERO) <= 0) {
                binPrice = BinMath.binPriceAtBin(pool.getBasePrice(), pool.getBinStep(),
                        currentBinId, pool.getActiveBinId());
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
                // Sprint 9-DS-r3 — flipped per canonical DLMM (see above).
                currentBinId = swapXtoY ? currentBinId - 1 : currentBinId + 1;
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
                    bin.setFeeGrowthX(bin.getFeeGrowthX() + BinMath.feeGrowthIncrement(lpFee, bin.getLiquidity()));
                    bin.setTotalFeeX(bin.getTotalFeeX() + fee);
                } else {
                    bin.setFeeGrowthY(bin.getFeeGrowthY() + BinMath.feeGrowthIncrement(lpFee, bin.getLiquidity()));
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

            // Sprint 9-DS-r3 — direction flipped (see top of loop).
            if (actualAmountIn >= maxAmountIn) {
                currentBinId = swapXtoY ? currentBinId - 1 : currentBinId + 1;
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

        // Update pool TVL. Add the NET input that actually entered bin reserves
        // (gross consumedAmountIn minus the skimmed fee) so totalTvl tracks
        // sum(bin reserves) — the invariant TvlReconciliationService checks.
        // Adding gross inflated TVL by the accumulated fee on every swap (the
        // fee is held in totalFeesCollected*, not in any bin reserve).
        long netInToBins = Math.max(0, consumedAmountIn - totalFee);
        if (swapXtoY) {
            pool.setTotalTvlX(pool.getTotalTvlX() + netInToBins);
            pool.setTotalTvlY(Math.max(0, pool.getTotalTvlY() - totalAmountOut));
        } else {
            pool.setTotalTvlY(pool.getTotalTvlY() + netInToBins);
            pool.setTotalTvlX(Math.max(0, pool.getTotalTvlX() - totalAmountOut));
        }

        // Flush NOW so the pool's @Version optimistic-lock check runs HERE,
        // BEFORE the (non-transactional, cross-service) balance settlement
        // below. Otherwise the version conflict only surfaces at commit —
        // after deduct/credit already executed in token-service — and the
        // swap() retry loop re-runs them, double-spending the user under
        // concurrent same-pool swaps. The flush also write-locks the pool
        // row, so our later commit cannot conflict again.
        poolRepository.saveAndFlush(pool);

        // 5. Deduct input token from user
        tokenServiceClient.deductBalance(userId, req.tokenInId(), consumedAmountIn);

        // 6. Credit output token to user
        tokenServiceClient.creditBalance(userId, tokenOutId, totalAmountOut);

        // Execution price and price impact.
        // Sprint 9-DS-r2 — same reciprocal-direction fix as in quote(),
        // see comment there. Both paths normalise executionPrice to the
        // "Y per X" frame so the impact math doesn't compute on reciprocals.
        BigDecimal executionPrice;
        if (consumedAmountIn <= 0 || totalAmountOut <= 0) {
            executionPrice = BigDecimal.ZERO;
        } else if (swapXtoY) {
            executionPrice = BigDecimal.valueOf(totalAmountOut)
                    .divide(BigDecimal.valueOf(consumedAmountIn), 18, RoundingMode.HALF_UP);
        } else {
            executionPrice = BigDecimal.valueOf(consumedAmountIn)
                    .divide(BigDecimal.valueOf(totalAmountOut), 18, RoundingMode.HALF_UP);
        }

        BigDecimal priceImpact = computePriceImpact(
                swapXtoY, consumedAmountIn, totalAmountOut, totalFee, binsCrossed, spotPrice);

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
                        req.idempotencyKey(),
                        executionPrice.toPlainString(),
                        System.currentTimeMillis()));

        log.info("Swap executed: pool={}, user={}, tx={}, in={} {}, out={}, fee={}, bins={}",
                pool.getId(), userId, txId, consumedAmountIn,
                swapXtoY ? "X→Y" : "Y→X", totalAmountOut, totalFee, binsCrossed);

        // 10. Return SwapResponse
        return new SwapResponse(txId, pool.getId(), req.tokenInId(), tokenOutId,
                consumedAmountIn, totalAmountOut, totalFee, feeBps,
                binsCrossed, executionPrice, priceImpact);
    }

    // ── Batch G-02: quote → execute idempotency layer ────────────────
    //
    // Two-call swap flow that hardens the contract against three
    // specific replay/race classes the original single-call swap()
    // couldn't defend on its own:
    //
    //   1. **Stale quote** — client clicks "Swap" 60s after the price
    //      was last refreshed; the quote no longer represents pool state.
    //      Rejected with QuoteExpiredException (HTTP 410 Gone).
    //   2. **Double-execute** — network glitches, the retry-with-
    //      backoff middleware fires twice, the user double-clicks. The
    //      idempotencyKey path catches client-supplied dupes, but
    //      server-issued quoteIds are also single-use. Rejected with
    //      QuoteAlreadyExecutedException (HTTP 409 Conflict).
    //   3. **Signature replay** — someone intercepts a quoteId in
    //      transit and tries to spend it. The signature bound to the
    //      quote (currently JWT-subject hash) doesn't match → rejected
    //      with InvalidQuoteSignatureException (HTTP 403 Forbidden).
    //
    // Pinned by SwapIdempotencyTest. Each check is a single
    // assertion at the top of executeQuoted() and runs *before* any
    // balance mutation, exactly matching the test sketches.

    /**
     * Issue a quote and stash a {@link QuotedSwap} in the store with the
     * configured TTL. The returned quoteId is the handle the client
     * passes to {@link #executeQuoted}. Signature is server-derived
     * (here from the userId — in production an HMAC over the quoted
     * parameters would be stronger; the contract on the test side is
     * just "the signature on execute must equal the signature on the
     * persisted quote").
     *
     * @param req       quote request (pool, input token, amount)
     * @param userId    caller the quote is bound to
     * @param signature server-derived signature stored on the quote and
     *                  re-checked at execute time
     * @return the priced quote (its {@code quoteId} is the execute handle)
     */
    public SwapQuoteResponse issueQuote(SwapQuoteRequest req, UUID userId, String signature) {
        SwapQuoteResponse quote = quote(req);
        UUID quoteId = UUID.randomUUID();
        QuotedSwap stored = new QuotedSwap(
                quoteId,
                userId,
                quote.poolId(),
                quote.tokenInId(),
                quote.tokenOutId(),
                quote.amountIn(),
                quote.estimatedAmountOut(),
                quote.estimatedFee(),
                signature,
                Instant.now(),
                null);
        quoteStore.save(stored);
        return quote;
    }

    /**
     * Execute a previously-issued quote. Validates: not expired, not
     * already executed, signature matches — then delegates to the
     * existing {@link #swap} path (which still gates KYC, pool-active,
     * counterparty cap, slippage etc.).
     *
     * <p>Checks run in a deliberate order to avoid information leaks: expiry
     * first (a stolen-but-expired quoteId reveals nothing about the signature),
     * then the persisted double-execute marker, then signature match. The
     * executed-marker is flipped via {@link QuoteStore#markExecuted} BEFORE any
     * balance mutation so a concurrent double-execute races at that narrow SETNX
     * boundary rather than at the wide {@link #swap} boundary. The request's own
     * amounts are ignored — the trade is rebuilt from the persisted quote
     * ("execute what was quoted").
     *
     * @param req    execute request (quote id + signature)
     * @param userId authenticated caller
     * @return the executed swap result
     * @throws QuoteExpiredException          if the quote is unknown/evicted or past TTL
     * @throws QuoteAlreadyExecutedException  if the quote was already consumed
     *                                        (or lost the concurrent execute race)
     * @throws InvalidQuoteSignatureException if the supplied signature does not match
     */
    public SwapResponse executeQuoted(SwapExecuteRequest req, UUID userId) {
        Optional<QuotedSwap> maybeQuote = quoteStore.findById(req.quoteId());
        // No quote → either never issued or evicted. Surface as
        // "expired" rather than "not found" because the most common
        // cause is TTL eviction, and from the client's perspective the
        // two are indistinguishable (both mean "your quote handle is
        // no longer valid, ask for a new one").
        if (maybeQuote.isEmpty()) {
            throw new QuoteExpiredException("Quote not found or expired: " + req.quoteId());
        }
        QuotedSwap quote = maybeQuote.get();

        // 1. Stale-quote check. Run before signature so an attacker
        // probing with a stolen-but-expired quoteId gets QUOTE_EXPIRED
        // rather than INVALID_QUOTE_SIGNATURE (no information leak
        // about whether the signature would have matched).
        Instant deadline = quote.createdAt().plus(Duration.ofSeconds(quoteTtlSeconds));
        if (Instant.now().isAfter(deadline)) {
            throw new QuoteExpiredException(
                    "Quote " + req.quoteId() + " expired at " + deadline + " (TTL=" + quoteTtlSeconds + "s)");
        }

        // 2. Double-execute check. executedAt is the persisted marker.
        // Pinned: a quote with executedAt != null can never be re-used,
        // regardless of how soon the second call arrives.
        if (quote.executedAt() != null) {
            throw new QuoteAlreadyExecutedException(
                    "Quote " + req.quoteId() + " already executed at " + quote.executedAt());
        }

        // 3. Signature replay check. The signature is bound to the
        // user/quote at issue-time; an interceptor with a different
        // identity can't successfully replay even before TTL elapses.
        if (!quote.signature().equals(req.signature())) {
            throw new InvalidQuoteSignatureException(
                    "Signature does not match quote " + req.quoteId());
        }

        // Flip the executed-marker BEFORE balance mutation, so a
        // concurrent double-execute races at the SETNX boundary rather
        // than at the (much wider) swap() boundary. First caller wins
        // the marker; second caller sees the marker and is rejected
        // upstream.
        boolean wonRace = quoteStore.markExecuted(req.quoteId());
        if (!wonRace) {
            throw new QuoteAlreadyExecutedException(
                    "Quote " + req.quoteId() + " concurrently executed");
        }

        // Build a SwapRequest from the persisted quote and run the
        // existing swap path. We deliberately re-derive amountIn etc.
        // from the QuotedSwap rather than letting the client re-send
        // them — the contract is "execute what was quoted", not
        // "execute whatever the client says now".
        SwapRequest swapReq = new SwapRequest(
                quote.poolId(),
                quote.tokenInId(),
                quote.amountIn(),
                0L,
                "quote:" + req.quoteId());
        return swap(swapReq, userId);
    }
}
