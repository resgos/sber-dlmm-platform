package com.sber.dlmm.pool.service;

import com.sber.dlmm.common.dto.PageResponse;
import com.sber.dlmm.common.enums.PoolStatus;
import com.sber.dlmm.common.exception.PoolNotActiveException;
import com.sber.dlmm.common.exception.PoolNotFoundException;
import com.sber.dlmm.common.exception.TokenNotFoundException;
import com.sber.dlmm.common.util.BinMath;
import com.sber.dlmm.common.util.FeeCalculator;
import com.sber.dlmm.pool.client.TokenServiceClient;
import com.sber.dlmm.pool.dto.BinResponse;
import com.sber.dlmm.pool.dto.PoolDetailResponse;
import com.sber.dlmm.pool.dto.PoolResponse;
import com.sber.dlmm.pool.dto.CreatePoolRequest;
import com.sber.dlmm.pool.entity.LiquidityPool;
import com.sber.dlmm.pool.entity.PoolBin;
import com.sber.dlmm.pool.event.PoolCreatedEvent;
import com.sber.dlmm.pool.repository.LiquidityPoolRepository;
import com.sber.dlmm.pool.repository.PoolBinRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.sber.dlmm.common.outbox.OutboxService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Pool lifecycle + read-model service for the DLMM engine: it creates pools,
 * exposes the catalogue / detail / bin views the UIs render, and applies the
 * admin control actions (pause, resume, emergency shutdown, fee + counterparty
 * limit changes).
 *
 * <p>It does <b>not</b> move liquidity or execute trades — {@link SwapService}
 * and {@link LiquidityService} own bin reserves and the F-12 invariant. This
 * service only reads bins and mutates pool-level metadata/status. Key
 * collaborators: {@link LiquidityPoolRepository} / {@link PoolBinRepository}
 * for persistence, {@link TokenServiceClient} to validate token pairs and
 * resolve symbols (batched to dodge N+1 calls on the listing), and
 * {@link OutboxService} so the {@code PoolCreated} event is published in the
 * same transaction as the insert (transactional-outbox pattern, never an inline
 * Kafka send).
 *
 * <p><b>Bin-id gotcha (read before touching the price fields):</b> pools follow
 * the Meteora-style convention where {@code activeBinId} is an ABSOLUTE id
 * (seed pools anchor at {@code 2^23 = 8_388_608}, the bin where price ≡
 * {@code basePrice}). Feeding that raw id into {@code BinMath.binPrice} computes
 * {@code basePrice * (1 + binStep/10000)^activeBinId} which overflows to
 * nonsense (~10^36000) and breaks JSON serialisation. Until the
 * absolute-vs-relative semantics are formalised, every "current price" surfaced
 * here is the stored {@code basePrice} verbatim — matching what the admin and
 * user pool pages display.
 */
@Service
public class PoolService {

    private static final Logger log = LoggerFactory.getLogger(PoolService.class);
    private static final String POOL_EVENTS_TOPIC = "pool-events";

    private final LiquidityPoolRepository poolRepository;
    private final PoolBinRepository poolBinRepository;
    private final TokenServiceClient tokenServiceClient;
    private final OutboxService outbox;

    /**
     * @param poolRepository     pool-row persistence (catalogue, status, fee
     *                           params, TVL/volume rollups)
     * @param poolBinRepository  bin-row reads for detail/bins responses
     * @param tokenServiceClient validates token pairs on creation and resolves
     *                           symbols (single + batch) for responses
     * @param outbox             transactional outbox used to publish
     *                           {@code PoolCreated} atomically with the insert
     */
    public PoolService(LiquidityPoolRepository poolRepository,
                       PoolBinRepository poolBinRepository,
                       TokenServiceClient tokenServiceClient,
                       OutboxService outbox) {
        this.poolRepository = poolRepository;
        this.poolBinRepository = poolBinRepository;
        this.tokenServiceClient = tokenServiceClient;
        this.outbox = outbox;
    }

    /**
     * Create a new, empty, ACTIVE pool for a token pair + bin step, validating
     * both tokens and enforcing pair uniqueness.
     *
     * <p>Business rules: both tokens must exist and be active (else
     * {@link TokenNotFoundException}); a pool with the same
     * {@code (tokenX, tokenY, binStep)} must not already exist (else surfaced
     * as {@link PoolNotActiveException}). The pool starts with
     * {@code basePrice = 1} as the anchor and an {@code activeBinId} derived
     * from the requested initial price via
     * {@link BinMath#priceToBinId(BigDecimal, int, BigDecimal)}; all reserves,
     * volume and fee accumulators start at zero. A {@code PoolCreated} event is
     * appended to the outbox inside this transaction so it ships iff the insert
     * commits.
     *
     * @param req         pool parameters (token pair, bin step, fee config,
     *                    decay period, protocol-fee %, initial price)
     * @param adminUserId id of the admin creating the pool, stamped as
     *                    {@code createdBy}
     * @return the created pool rendered with both token symbols
     * @throws TokenNotFoundException if either token is missing or inactive
     * @throws PoolNotActiveException if a pool already exists for this pair +
     *                                bin step
     */
    @Transactional
    public PoolResponse createPool(CreatePoolRequest req, UUID adminUserId) {
        TokenServiceClient.TokenInfo tokenX = tokenServiceClient.getToken(req.tokenXId());
        if (tokenX == null || !tokenX.active()) {
            throw new TokenNotFoundException("Token X not found or inactive: " + req.tokenXId());
        }
        TokenServiceClient.TokenInfo tokenY = tokenServiceClient.getToken(req.tokenYId());
        if (tokenY == null || !tokenY.active()) {
            throw new TokenNotFoundException("Token Y not found or inactive: " + req.tokenYId());
        }

        poolRepository.findByTokenXIdAndTokenYIdAndBinStep(req.tokenXId(), req.tokenYId(), req.binStep())
                .ifPresent(existing -> {
                    throw new PoolNotActiveException("Pool already exists for this token pair and bin step");
                });

        BigDecimal basePrice = BigDecimal.ONE;
        int activeBinId = BinMath.priceToBinId(basePrice, req.binStep(), req.initialPrice());

        LiquidityPool pool = LiquidityPool.builder()
                .id(UUID.randomUUID())
                .tokenXId(req.tokenXId())
                .tokenYId(req.tokenYId())
                .binStep(req.binStep())
                .baseFeeBps(req.baseFeeBps())
                .maxVariableFeeBps(req.maxVariableFeeBps())
                .volatilityAccumulator(0)
                .decayPeriodSeconds(req.decayPeriodSeconds())
                .activeBinId(activeBinId)
                .basePrice(basePrice)
                .protocolFeePct(req.protocolFeePct())
                .totalTvlX(0)
                .totalTvlY(0)
                .volume24h(0)
                .totalFeesCollectedX(0)
                .totalFeesCollectedY(0)
                .status(PoolStatus.ACTIVE)
                .createdBy(adminUserId)
                .createdAt(LocalDateTime.now())
                .build();

        poolRepository.save(pool);

        log.info("Pool created: id={}, tokens=({},{}), binStep={}, activeBin={}",
                pool.getId(), tokenX.symbol(), tokenY.symbol(), req.binStep(), activeBinId);

        outbox.append("pool", pool.getId().toString(), "PoolCreated", POOL_EVENTS_TOPIC,
                new PoolCreatedEvent(pool.getId(), pool.getTokenXId(), pool.getTokenYId(),
                        pool.getBinStep(), pool.getCreatedAt()));

        return toPoolResponse(pool, tokenX.symbol(), tokenY.symbol());
    }

    /**
     * Paginated pool catalogue for the listing pages.
     *
     * <p>Sorting by {@code "tvl"} is special-cased to a dedicated repository
     * query ({@code findTopByTvl}) because TVL is a derived rollup, not a
     * single sortable column; any other key sorts descending on that field
     * (defaulting to {@code createdAt}). Token symbols for the whole page are
     * resolved in <b>one</b> batch call to token-service rather than a per-pool
     * GET — the old N+1 loop made up to 2N round-trips (44 for 22 seed pools)
     * and pushed listing latency into multiple seconds.
     *
     * @param page   zero-based page index
     * @param size   page size
     * @param sortBy sort key; {@code "tvl"} triggers the rollup-ordered query,
     *               otherwise the field to sort DESC by (null → {@code createdAt})
     * @return a page of pool responses with token symbols populated
     */
    public PageResponse<PoolResponse> getAllPools(int page, int size, String sortBy) {
        Sort sort = "tvl".equalsIgnoreCase(sortBy)
                ? Sort.unsorted()
                : Sort.by(Sort.Direction.DESC, sortBy != null ? sortBy : "createdAt");

        Page<LiquidityPool> poolPage;
        if ("tvl".equalsIgnoreCase(sortBy)) {
            poolPage = poolRepository.findTopByTvl(PageRequest.of(page, size));
        } else {
            poolPage = poolRepository.findAll(PageRequest.of(page, size, sort));
        }

        // Resolve token symbols in one bulk call instead of N+1 GET /tokens/{id}.
        // Previous per-pool loop made up to 2N WebClient round-trips
        // (44 calls for 22 seed pools) and pushed /api/v1/pools latency to
        // ~7s+ even after the BinMath fast-pow fix. The batch endpoint on
        // token-service is a single network hit.
        java.util.Set<UUID> uniqueIds = new java.util.HashSet<>();
        for (LiquidityPool pool : poolPage.getContent()) {
            uniqueIds.add(pool.getTokenXId());
            uniqueIds.add(pool.getTokenYId());
        }
        java.util.Map<UUID, TokenServiceClient.TokenInfo> tokens =
                tokenServiceClient.getTokensByIds(uniqueIds);

        List<PoolResponse> responses = poolPage.getContent().stream()
                .map(pool -> {
                    var tx = tokens.get(pool.getTokenXId());
                    var ty = tokens.get(pool.getTokenYId());
                    return toPoolResponse(pool, tx != null ? tx.symbol() : null,
                            ty != null ? ty.symbol() : null);
                })
                .toList();

        return new PageResponse<>(responses, page, size,
                poolPage.getTotalElements(), poolPage.getTotalPages());
    }

    /**
     * Full detail view for one pool: header + the populated (liquidity-bearing)
     * bins + live fee/APY figures.
     *
     * <p>Returns only bins that currently hold liquidity
     * ({@code findActiveBins}) so the bin chart isn't padded with empty slots.
     * Token-symbol resolution is best-effort — a failure to resolve either
     * symbol is swallowed and the response simply carries a null symbol rather
     * than failing the whole detail call. The headline fee is the current
     * dynamic fee (base + volatility surcharge, capped) and the APY is the
     * fee-revenue estimate computed against that same fee, so the two stay
     * consistent.
     *
     * @param poolId pool to describe
     * @return header, active bins, volatility accumulator, current dynamic fee,
     *         and collected-fee totals
     * @throws PoolNotFoundException if no pool has that id
     */
    public PoolDetailResponse getPoolDetail(UUID poolId) {
        LiquidityPool pool = poolRepository.findById(poolId)
                .orElseThrow(() -> new PoolNotFoundException("Pool not found: " + poolId));

        List<PoolBin> activeBins = poolBinRepository.findActiveBins(poolId);
        List<BinResponse> binResponses = activeBins.stream()
                .map(bin -> new BinResponse(bin.getBinId(), bin.getPrice(), bin.getLiquidity(),
                        bin.getReserveX(), bin.getReserveY(), bin.getCompositionFactor()))
                .toList();

        // Resolve token symbols
        String symX = null, symY = null;
        try { var tx = tokenServiceClient.getToken(pool.getTokenXId()); if (tx != null) symX = tx.symbol(); } catch (Exception ignored) {}
        try { var ty = tokenServiceClient.getToken(pool.getTokenYId()); if (ty != null) symY = ty.symbol(); } catch (Exception ignored) {}

        int currentDynamicFeeBps = calculateDynamicFee(pool);
        BigDecimal estimatedApy = calculateEstimatedApy(pool, currentDynamicFeeBps);

        PoolResponse poolResponse = toPoolResponseWithApy(pool, symX, symY, estimatedApy);

        return new PoolDetailResponse(poolResponse, binResponses,
                pool.getVolatilityAccumulator(), currentDynamicFeeBps,
                pool.getTotalFeesCollectedX(), pool.getTotalFeesCollectedY());
    }

    /**
     * Return every bin of a pool within an inclusive id window — backs the
     * order-book / depth view which asks for a bounded slice around the active
     * bin.
     *
     * <p>Existence is checked up front so a bad pool id yields a clean 404
     * rather than an empty list that the caller can't distinguish from "no bins
     * in range".
     *
     * @param poolId  pool whose bins to read
     * @param fromBin inclusive lower bin id
     * @param toBin   inclusive upper bin id
     * @return bins in {@code [fromBin, toBin]} (possibly empty)
     * @throws PoolNotFoundException if no pool has that id
     */
    public List<BinResponse> getPoolBins(UUID poolId, int fromBin, int toBin) {
        if (!poolRepository.existsById(poolId)) {
            throw new PoolNotFoundException("Pool not found: " + poolId);
        }
        List<PoolBin> bins = poolBinRepository.findByPoolIdAndBinIdBetween(poolId, fromBin, toBin);
        return bins.stream()
                .map(bin -> new BinResponse(bin.getBinId(), bin.getPrice(), bin.getLiquidity(),
                        bin.getReserveX(), bin.getReserveY(), bin.getCompositionFactor()))
                .toList();
    }

    /**
     * Admin action: pause a pool. {@link PoolStatus#PAUSED} pools reject new
     * swaps and add-liquidity (those paths gate on {@code ACTIVE}); this is the
     * reversible control, undone by {@link #resumePool(UUID)}.
     *
     * @param poolId pool to pause
     * @return the updated pool (symbols omitted — admin action response)
     * @throws PoolNotFoundException if no pool has that id
     */
    @Transactional
    public PoolResponse pausePool(UUID poolId) {
        LiquidityPool pool = poolRepository.findById(poolId)
                .orElseThrow(() -> new PoolNotFoundException("Pool not found: " + poolId));
        pool.setStatus(PoolStatus.PAUSED);
        poolRepository.save(pool);
        log.info("Pool paused: {}", poolId);
        return toPoolResponse(pool, null, null);
    }

    /**
     * Admin break-glass action: move a pool to
     * {@link PoolStatus#EMERGENCY_SHUTDOWN}. Logged at WARN (vs INFO for a
     * normal pause) because it is the incident-grade halt. Like pause it blocks
     * the {@code ACTIVE}-gated trade/deposit paths; intended for use when a pool
     * is suspected compromised or mispriced.
     *
     * @param poolId pool to shut down
     * @return the updated pool (symbols omitted)
     * @throws PoolNotFoundException if no pool has that id
     */
    @Transactional
    public PoolResponse emergencyShutdown(UUID poolId) {
        LiquidityPool pool = poolRepository.findById(poolId)
                .orElseThrow(() -> new PoolNotFoundException("Pool not found: " + poolId));
        pool.setStatus(PoolStatus.EMERGENCY_SHUTDOWN);
        poolRepository.save(pool);
        log.warn("Pool emergency shutdown: {}", poolId);
        return toPoolResponse(pool, null, null);
    }

    /**
     * Admin action: return a pool to {@link PoolStatus#ACTIVE}, re-enabling
     * swaps and deposits. The inverse of {@link #pausePool(UUID)} /
     * {@link #emergencyShutdown(UUID)}; performs no extra safety check, so the
     * operator is responsible for confirming the pool is healthy before
     * resuming.
     *
     * @param poolId pool to reactivate
     * @return the updated pool (symbols omitted)
     * @throws PoolNotFoundException if no pool has that id
     */
    @Transactional
    public PoolResponse resumePool(UUID poolId) {
        LiquidityPool pool = poolRepository.findById(poolId)
                .orElseThrow(() -> new PoolNotFoundException("Pool not found: " + poolId));
        pool.setStatus(PoolStatus.ACTIVE);
        poolRepository.save(pool);
        log.info("Pool resumed: {}", poolId);
        return toPoolResponse(pool, null, null);
    }

    /**
     * Admin action: retune a pool's variable-fee parameters live.
     *
     * <p>Sets the base fee, the variable-fee ceiling, and the volatility
     * decay period; the new values take effect on the next swap and the next
     * {@link PoolScheduledTasks#decayVolatilityAccumulators()} tick. Does not
     * touch the accumulator itself — only the curve it moves along.
     *
     * @param poolId             pool to retune
     * @param baseFeeBps         new base fee in basis points
     * @param maxVariableFeeBps  new cap on the volatility-driven surcharge (bps)
     * @param decayPeriodSeconds new decay period feeding the per-tick decay rate
     * @return the updated pool (symbols omitted)
     * @throws PoolNotFoundException if no pool has that id
     */
    @Transactional
    public PoolResponse updateFeeParams(UUID poolId, int baseFeeBps,
                                         int maxVariableFeeBps, int decayPeriodSeconds) {
        LiquidityPool pool = poolRepository.findById(poolId)
                .orElseThrow(() -> new PoolNotFoundException("Pool not found: " + poolId));
        pool.setBaseFeeBps(baseFeeBps);
        pool.setMaxVariableFeeBps(maxVariableFeeBps);
        pool.setDecayPeriodSeconds(decayPeriodSeconds);
        poolRepository.save(pool);
        log.info("Pool fee params updated: pool={}, baseFee={}, maxVarFee={}, decay={}",
                poolId, baseFeeBps, maxVariableFeeBps, decayPeriodSeconds);
        return toPoolResponse(pool, null, null);
    }

    /**
     * Sprint 6 #3.1 — admin sets per-pool protocol fee percent.
     *
     * <p>Range 0-5 enforced by DTO validation per Sprint 5 #5.G legal memo
     * verdict ("≤5% stays within internal-clearing reg-frame"). At 0%
     * the pool runs in pure-LP mode (Sprint 1-2 behaviour pre-#3.2).
     * At 5% one in twenty units of each fee accrues to the protocol
     * treasury via {@code totalProtocolFeeX/Y} accumulators.
     */
    @Transactional
    public PoolResponse updateProtocolFeePct(UUID poolId, int protocolFeePct) {
        if (protocolFeePct < 0 || protocolFeePct > 5) {
            throw new IllegalArgumentException(
                    "protocolFeePct must be in [0..5] per 3.A legal memo, got " + protocolFeePct);
        }
        LiquidityPool pool = poolRepository.findById(poolId)
                .orElseThrow(() -> new PoolNotFoundException("Pool not found: " + poolId));
        int previous = pool.getProtocolFeePct();
        pool.setProtocolFeePct(protocolFeePct);
        poolRepository.save(pool);
        log.info("Pool protocol fee updated: pool={} {}%→{}%", poolId, previous, protocolFeePct);
        return toPoolResponse(pool, null, null);
    }

    /**
     * Sprint 4 #4.2 — admin sets per-pool single-swap counterparty caps.
     *
     * Either field may be null. Null = "no cap" (full pre-Sprint-4 behaviour).
     * Distinguishing "leave as-is" from "reset to null" is the caller's
     * job — they always send the full target state, so what arrives wins.
     * Frontend (admin-ui) should pre-fill with current values to avoid
     * accidental cap removal.
     */
    @Transactional
    public PoolResponse updateCounterpartyLimits(UUID poolId,
                                                  Long maxSingleSwapNominalX,
                                                  Long maxSingleSwapNominalY) {
        LiquidityPool pool = poolRepository.findById(poolId)
                .orElseThrow(() -> new PoolNotFoundException("Pool not found: " + poolId));
        pool.setMaxSingleSwapNominalX(maxSingleSwapNominalX);
        pool.setMaxSingleSwapNominalY(maxSingleSwapNominalY);
        poolRepository.save(pool);
        log.info("Pool counterparty limits updated: pool={}, maxX={}, maxY={}",
                poolId, maxSingleSwapNominalX, maxSingleSwapNominalY);
        return toPoolResponse(pool, null, null);
    }

    /**
     * Current effective fee (bps) = base fee + the volatility surcharge implied
     * by the pool's accumulator and bin step, capped at the engine's
     * {@code MAX_FEE_BPS}.
     *
     * <p>Delegates to {@link FeeCalculator#totalFeeBps(int, int, int)} — the
     * SAME function {@link FeeCalculator#calculateSwapFee} uses — so the
     * "current dynamic fee" shown in the UI is exactly the fee a swap would be
     * charged right now. Previously this was a duplicated formula that could
     * silently drift from the charged fee.
     *
     * @param pool pool whose live fee to compute
     * @return total dynamic fee in basis points (capped)
     */
    private int calculateDynamicFee(LiquidityPool pool) {
        // Shared with FeeCalculator.calculateSwapFee so the displayed "current
        // dynamic fee" and the fee actually charged stay identical (and both honour
        // the 10% MAX_FEE_BPS cap). Was a duplicated formula that could drift.
        return FeeCalculator.totalFeeBps(pool.getBaseFeeBps(),
                pool.getVolatilityAccumulator(), pool.getBinStep());
    }

    /**
     * Estimate the pool's fee APY from recent activity:
     * {@code (volume24h × feeBps/10000 × 365) / totalTvl × 100}.
     *
     * <p>This is the fee-yield headline only (no impermanent-loss term). It
     * deliberately returns {@code 0} when there is no TVL or no 24h volume so an
     * idle/empty pool advertises 0% rather than a divide-by-zero or a stale
     * number. {@code totalTvl} sums the X and Y rollups (the engine's existing
     * mixed-unit convention); a canonical single-unit measure is a future
     * refactor.
     *
     * @param pool          pool to score
     * @param dynamicFeeBps the fee rate to assume (normally
     *                      {@link #calculateDynamicFee(LiquidityPool)})
     * @return estimated APY as a percentage (2 dp), or zero when inputs are zero
     */
    private BigDecimal calculateEstimatedApy(LiquidityPool pool, int dynamicFeeBps) {
        long totalTvl = pool.getTotalTvlX() + pool.getTotalTvlY();
        if (totalTvl == 0 || pool.getVolume24h() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal dailyFeeRevenue = BigDecimal.valueOf(pool.getVolume24h())
                .multiply(BigDecimal.valueOf(dynamicFeeBps))
                .divide(BigDecimal.valueOf(10_000), 18, RoundingMode.HALF_UP);
        BigDecimal annualFeeRevenue = dailyFeeRevenue.multiply(BigDecimal.valueOf(365));
        return annualFeeRevenue
                .divide(BigDecimal.valueOf(totalTvl), 18, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Map a pool entity to its API response, computing the live dynamic fee and
     * APY on the fly. Symbols are passed in (the caller decides whether to spend
     * a token-service lookup); admin actions pass nulls.
     *
     * <p>Exposes {@code basePrice} as the response's current price rather than
     * deriving it from {@code activeBinId} — see the class Javadoc bin-id gotcha
     * (the absolute anchor id overflows {@code BinMath.binPrice}).
     *
     * @param pool        pool to render
     * @param tokenXSymbol resolved X symbol, or null if not looked up
     * @param tokenYSymbol resolved Y symbol, or null if not looked up
     * @return the pool response with price/fee/APY filled in
     */
    private PoolResponse toPoolResponse(LiquidityPool pool, String tokenXSymbol, String tokenYSymbol) {
        // pool.activeBinId follows the Meteora-style "middle bin = 2^23 (8_388_608)"
        // convention — it's an ABSOLUTE bin id calibrated so that bin 8_388_608
        // equals pool.basePrice. Feeding the raw activeBinId straight into
        // BinMath.binPrice computes basePrice * (1 + binStep/10000)^8_388_608
        // (~10^36000 for SBTC) — semantically meaningless and overflows any
        // downstream JSON consumer (Jackson rejects it). Until the
        // absolute-vs-relative bin-id semantics are formalised (open backlog),
        // expose basePrice as the listing-level current price.
        BigDecimal currentPrice = pool.getBasePrice();
        int dynamicFeeBps = calculateDynamicFee(pool);
        BigDecimal apy = calculateEstimatedApy(pool, dynamicFeeBps);
        return new PoolResponse(pool.getId(), pool.getTokenXId(), pool.getTokenYId(),
                tokenXSymbol, tokenYSymbol, pool.getBinStep(), pool.getBaseFeeBps(),
                pool.getActiveBinId(), currentPrice, pool.getTotalTvlX(), pool.getTotalTvlY(),
                pool.getVolume24h(), apy, pool.getStatus(), pool.getCreatedAt(),
                pool.getTotalFeesCollectedX(), pool.getTotalFeesCollectedY());
    }

    /**
     * Variant of {@link #toPoolResponse} that takes a pre-computed APY instead
     * of recomputing it — used by {@link #getPoolDetail(UUID)} where the APY was
     * already derived alongside the current dynamic fee, avoiding a redundant
     * second computation. Same {@code basePrice}-as-current-price rule applies.
     *
     * @param pool         pool to render
     * @param tokenXSymbol resolved X symbol, or null
     * @param tokenYSymbol resolved Y symbol, or null
     * @param apy          APY to embed (caller-supplied)
     * @return the pool response carrying the supplied APY
     */
    private PoolResponse toPoolResponseWithApy(LiquidityPool pool, String tokenXSymbol,
                                                 String tokenYSymbol, BigDecimal apy) {
        // See toPoolResponse(...) comment above for why basePrice is used directly
        // instead of BinMath.binPrice(base, step, activeBinId).
        BigDecimal currentPrice = pool.getBasePrice();
        return new PoolResponse(pool.getId(), pool.getTokenXId(), pool.getTokenYId(),
                tokenXSymbol, tokenYSymbol, pool.getBinStep(), pool.getBaseFeeBps(),
                pool.getActiveBinId(), currentPrice, pool.getTotalTvlX(), pool.getTotalTvlY(),
                pool.getVolume24h(), apy, pool.getStatus(), pool.getCreatedAt(),
                pool.getTotalFeesCollectedX(), pool.getTotalFeesCollectedY());
    }
}
