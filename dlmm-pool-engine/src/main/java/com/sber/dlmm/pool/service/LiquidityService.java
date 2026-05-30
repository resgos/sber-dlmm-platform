package com.sber.dlmm.pool.service;

import com.sber.dlmm.common.enums.LiquidityStrategy;
import com.sber.dlmm.common.enums.PoolStatus;
import com.sber.dlmm.common.exception.ForbiddenException;
import com.sber.dlmm.common.exception.IdempotencyConflictException;
import com.sber.dlmm.common.exception.InsufficientBalanceException;
import com.sber.dlmm.common.exception.InvalidBinRangeException;
import com.sber.dlmm.common.exception.PoolNotActiveException;
import com.sber.dlmm.common.exception.PoolNotFoundException;
import com.sber.dlmm.common.util.BinMath;
import com.sber.dlmm.pool.client.TokenServiceClient;
import com.sber.dlmm.pool.client.UserServiceClient;
import com.sber.dlmm.pool.dto.AddLiquidityRequest;
import com.sber.dlmm.pool.dto.AddLiquidityResponse;
import com.sber.dlmm.pool.dto.BinAllocation;
import com.sber.dlmm.pool.dto.PositionResponse;
import com.sber.dlmm.pool.dto.PreviewAddLiquidityResponse;
import com.sber.dlmm.pool.dto.RemoveLiquidityRequest;
import com.sber.dlmm.pool.dto.RemoveLiquidityResponse;
import com.sber.dlmm.pool.entity.LiquidityPool;
import com.sber.dlmm.pool.entity.LpPosition;
import com.sber.dlmm.pool.entity.PoolBin;
import com.sber.dlmm.pool.entity.PositionBin;
import com.sber.dlmm.pool.event.LiquidityAddedEvent;
import com.sber.dlmm.pool.event.LiquidityRemovedEvent;
import com.sber.dlmm.pool.repository.LiquidityPoolRepository;
import com.sber.dlmm.pool.repository.LpPositionRepository;
import com.sber.dlmm.pool.repository.PoolBinRepository;
import com.sber.dlmm.pool.repository.PositionBinRepository;
import com.sber.dlmm.common.outbox.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class LiquidityService {

    private static final Logger log = LoggerFactory.getLogger(LiquidityService.class);
    private static final String POOL_EVENTS_TOPIC = "pool-events";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final String IDEMPOTENCY_PREFIX = "idempotency:liquidity:";

    private final LiquidityPoolRepository poolRepository;
    private final PoolBinRepository poolBinRepository;
    private final LpPositionRepository positionRepository;
    private final PositionBinRepository positionBinRepository;
    private final TokenServiceClient tokenServiceClient;
    private final UserServiceClient userServiceClient;
    // Replaced direct KafkaTemplate with the transactional outbox.
    private final OutboxService outbox;
    private final StringRedisTemplate redisTemplate;

    // Sprint 3 #3.4 — exit fee on LP close in basis points.
    // 10 bps = 0.10% of withdrawn principal (NOT of fees earned).
    // Tunable via env so we can A/B in pilot pools without redeploy.
    // Set to 0 to disable.
    @Value("${dlmm.fees.lp-exit-bps:10}")
    private int lpExitFeeBps;

    public LiquidityService(LiquidityPoolRepository poolRepository,
                            PoolBinRepository poolBinRepository,
                            LpPositionRepository positionRepository,
                            PositionBinRepository positionBinRepository,
                            TokenServiceClient tokenServiceClient,
                            UserServiceClient userServiceClient,
                            OutboxService outbox,
                            StringRedisTemplate redisTemplate) {
        this.poolRepository = poolRepository;
        this.poolBinRepository = poolBinRepository;
        this.positionRepository = positionRepository;
        this.positionBinRepository = positionBinRepository;
        this.tokenServiceClient = tokenServiceClient;
        this.userServiceClient = userServiceClient;
        this.outbox = outbox;
        this.redisTemplate = redisTemplate;
    }

    @Transactional
    public AddLiquidityResponse addLiquidity(AddLiquidityRequest req, UUID userId) {
        // 1. Check pool is ACTIVE
        LiquidityPool pool = poolRepository.findById(req.poolId())
                .orElseThrow(() -> new PoolNotFoundException("Pool not found: " + req.poolId()));
        if (pool.getStatus() != PoolStatus.ACTIVE) {
            throw new PoolNotActiveException("Pool is not active: " + pool.getStatus());
        }

        // Verify tokens are active
        if (!tokenServiceClient.isTokenActive(pool.getTokenXId())) {
            throw new PoolNotActiveException("Token X is not active");
        }
        if (!tokenServiceClient.isTokenActive(pool.getTokenYId())) {
            throw new PoolNotActiveException("Token Y is not active");
        }

        // Verify KYC
        if (!userServiceClient.isUserKycVerified(userId)) {
            throw new ForbiddenException("User KYC not verified");
        }

        // Sprint 6 #6.7 — 115-ФЗ самозапрет gate. add-liquidity =
        // new-money-out path. remove-liquidity is NOT gated (closing
        // existing positions stays possible for restricted users).
        if (userServiceClient.isUserSelfRestricted(userId)) {
            throw new com.sber.dlmm.common.exception.UserSelfRestrictedException(
                    "Установлен самозапрет (115-ФЗ). Новые позиции запрещены.");
        }

        // 2. Idempotency check
        if (req.idempotencyKey() != null && !req.idempotencyKey().isBlank()) {
            String redisKey = IDEMPOTENCY_PREFIX + req.idempotencyKey();
            Boolean wasAbsent = redisTemplate.opsForValue()
                    .setIfAbsent(redisKey, "processing", Duration.ofHours(24));
            if (Boolean.FALSE.equals(wasAbsent)) {
                throw new IdempotencyConflictException("Duplicate request: " + req.idempotencyKey());
            }
        }

        // Validate bin range
        int binRangeMin = req.binRangeMin();
        int binRangeMax = req.binRangeMax();
        if (binRangeMin > binRangeMax) {
            throw new InvalidBinRangeException("binRangeMin must be <= binRangeMax");
        }
        int numBins = binRangeMax - binRangeMin + 1;
        if (numBins <= 0 || numBins > 1000) {
            throw new InvalidBinRangeException("Bin range must be between 1 and 1000 bins");
        }

        int activeBinId = pool.getActiveBinId();
        BigDecimal basePrice = pool.getBasePrice();
        int binStep = pool.getBinStep();

        // 3. Calculate distribution weights per strategy
        long totalLiquidity = req.amountX() + req.amountY();
        double[] weights = calculateDistributionWeights(req.strategy(), binRangeMin, binRangeMax, activeBinId);

        // 4. For each bin, calculate allocation and create/update bin records
        List<BinAllocation> allocations = new ArrayList<>();
        long totalDepositedX = 0;
        long totalDepositedY = 0;
        long totalShares = 0;
        UUID positionId = UUID.randomUUID();

        List<PositionBin> positionBins = new ArrayList<>();

        for (int binId = binRangeMin; binId <= binRangeMax; binId++) {
            int idx = binId - binRangeMin;
            long binLiquidity = (long) (totalLiquidity * weights[idx]);
            if (binLiquidity <= 0) {
                continue;
            }

            // Sprint 9-DS-r3 — TWO BUGS fixed in this block:
            //
            // (a) BinMath.binPrice(basePrice, binStep, binId) treats binId
            //     as an offset from the *zero* bin. Seed pools use
            //     activeBinId = 2^23 = 8 388 608 as the anchor where
            //     price == basePrice, so the formula overflowed to
            //     exp(4192) and every below-active bin computed
            //     amountX = binLiquidity / astronomical = 0 (FLOOR).
            //     Result: any add-liquidity call with the user range
            //     straddling the active bin deposited ONLY Y — every
            //     X-side bin silently swallowed nothing (the response's
            //     depositedX was always 0). Fix: compute price relative
            //     to activeBinId (subtract the offset).
            //
            // (b) The X/Y side assignment was inverted vs canonical DLMM
            //     (and vs seed bin data, which has Y-only below price
            //     and X-only above). In standard DLMM with price=Y/X,
            //     bins below the active price hold Y (waiting to buy X
            //     cheap) and bins above hold X. Code said the opposite.
            //     Fix: swap the two branches.
            // Sprint 9-DS-r4 (P0-2) — through BinMath.binPriceAtBin
            // helper so the activeBinId-offset subtraction can't be
            // dropped at the call site (the original Sprint 9-DS-r3
            // bug). The helper does exactly `binId - activeBinId` for
            // us; encapsulated so a future careless edit can't undo it.
            BigDecimal binPrice = BinMath.binPriceAtBin(basePrice, binStep, binId, activeBinId);

            // Sprint 11 G-22 — shared with previewAddLiquidity. See
            // computeBinAmounts() Javadoc for the canonical-DLMM side
            // assignment + Sprint 9-DS-r3 composition-factor clamp.
            long[] amounts = computeBinAmounts(pool, binId, binLiquidity, binPrice);
            long amountX = Math.min(amounts[0], req.amountX() - totalDepositedX);
            long amountY = Math.min(amounts[1], req.amountY() - totalDepositedY);

            if (amountX <= 0 && amountY <= 0) {
                continue;
            }

            // Create or update PoolBin
            PoolBin poolBin = poolBinRepository.findByPoolIdAndBinId(pool.getId(), binId)
                    .orElse(PoolBin.builder()
                            .poolId(pool.getId())
                            .binId(binId)
                            .price(binPrice)
                            .liquidity(0)
                            .reserveX(0)
                            .reserveY(0)
                            .compositionFactor(BigDecimal.ZERO)
                            .totalFeeX(0)
                            .totalFeeY(0)
                            .feeGrowthX(0)
                            .feeGrowthY(0)
                            .build());

            poolBin.setLiquidity(poolBin.getLiquidity() + binLiquidity);
            poolBin.setReserveX(poolBin.getReserveX() + amountX);
            poolBin.setReserveY(poolBin.getReserveY() + amountY);
            poolBin.setCompositionFactor(BinMath.compositionFactor(poolBin.getReserveY(), poolBin.getLiquidity()));
            poolBinRepository.save(poolBin);

            // Create PositionBin
            PositionBin posBin = PositionBin.builder()
                    .positionId(positionId)
                    .binId(binId)
                    .liquidityShares(binLiquidity)
                    .build();
            positionBins.add(posBin);

            allocations.add(new BinAllocation(binId, amountX, amountY, binLiquidity));

            totalDepositedX += amountX;
            totalDepositedY += amountY;
            totalShares += binLiquidity;
        }

        if (totalShares == 0) {
            throw new InvalidBinRangeException("No liquidity could be allocated to any bin");
        }

        // 5. Deduct tokens from user balance
        if (totalDepositedX > 0) {
            tokenServiceClient.deductBalance(userId, pool.getTokenXId(), totalDepositedX);
        }
        if (totalDepositedY > 0) {
            tokenServiceClient.deductBalance(userId, pool.getTokenYId(), totalDepositedY);
        }

        // 6. Update pool TVL
        pool.setTotalTvlX(pool.getTotalTvlX() + totalDepositedX);
        pool.setTotalTvlY(pool.getTotalTvlY() + totalDepositedY);
        poolRepository.save(pool);

        // Save position bins
        positionBinRepository.saveAll(positionBins);

        // 7. Create LpPosition
        // Get current feeGrowth from the active bin (or first bin in range) for snapshot
        long lastFeeGrowthX = 0;
        long lastFeeGrowthY = 0;
        poolBinRepository.findByPoolIdAndBinId(pool.getId(), activeBinId).ifPresent(ab -> {
            // Snapshot is taken at position creation time — stored in a mutable holder
        });
        PoolBin activeBin = poolBinRepository.findByPoolIdAndBinId(pool.getId(), activeBinId).orElse(null);
        if (activeBin != null) {
            lastFeeGrowthX = activeBin.getFeeGrowthX();
            lastFeeGrowthY = activeBin.getFeeGrowthY();
        }

        LpPosition position = LpPosition.builder()
                .id(positionId)
                .userId(userId)
                .poolId(pool.getId())
                .binRangeMin(binRangeMin)
                .binRangeMax(binRangeMax)
                .strategy(req.strategy())
                .totalLiquidityShares(totalShares)
                .unclaimedFeeX(0)
                .unclaimedFeeY(0)
                .lastFeeGrowthX(lastFeeGrowthX)
                .lastFeeGrowthY(lastFeeGrowthY)
                // Sprint 9-DS-r4 (P1-10) — cost-basis snapshot for the
                // P&L column on PositionsPage. Stamped at position
                // creation; partial removes scale this down proportionally
                // (see removeLiquidity below).
                .initialDepositX(totalDepositedX)
                .initialDepositY(totalDepositedY)
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .build();
        positionRepository.save(position);

        // 8. Outbox: durable LiquidityAdded event
        outbox.append("position", positionId.toString(), "LiquidityAdded", POOL_EVENTS_TOPIC,
                new LiquidityAddedEvent(pool.getId(), userId, positionId, totalDepositedX, totalDepositedY));

        log.info("Liquidity added: pool={}, user={}, position={}, depositedX={}, depositedY={}, shares={}",
                pool.getId(), userId, positionId, totalDepositedX, totalDepositedY, totalShares);

        // 9. Return response
        return new AddLiquidityResponse(positionId, pool.getId(), binRangeMin, binRangeMax,
                req.strategy(), totalDepositedX, totalDepositedY, totalShares, allocations);
    }

    /**
     * Sprint 11 G-22 — read-only "what-if" pricing for an add-liquidity
     * call. Computes the same bin distribution as
     * {@link #addLiquidity(AddLiquidityRequest, UUID)} but writes
     * nothing — no DB mutation, no balance deduction, no idempotency
     * check, no Kafka event.
     *
     * <p>UI fetches this on every form-field change (debounced) so the
     * user sees TVL share, in-range chip, fee-per-day projection, and
     * warnings before committing. Solves Dmitry's "how much does my
     * add shift the price?" question (medium-business request,
     * Sprint 11 backlog).
     */
    @Transactional(readOnly = true)
    public PreviewAddLiquidityResponse previewAddLiquidity(AddLiquidityRequest req) {
        // Same pool + bin-range validation as the write path. Cheaper to
        // throw early than dump invalid data into the preview response.
        LiquidityPool pool = poolRepository.findById(req.poolId())
                .orElseThrow(() -> new PoolNotFoundException("Pool not found: " + req.poolId()));
        if (pool.getStatus() != PoolStatus.ACTIVE) {
            throw new PoolNotActiveException("Pool is not active: " + pool.getStatus());
        }

        int binRangeMin = req.binRangeMin();
        int binRangeMax = req.binRangeMax();
        if (binRangeMin > binRangeMax) {
            throw new InvalidBinRangeException("binRangeMin must be <= binRangeMax");
        }
        int numBins = binRangeMax - binRangeMin + 1;
        if (numBins <= 0 || numBins > 1000) {
            throw new InvalidBinRangeException("Bin range must be between 1 and 1000 bins");
        }

        int activeBinId = pool.getActiveBinId();
        BigDecimal basePrice = pool.getBasePrice();
        int binStep = pool.getBinStep();

        // Reuse the exact distribution formula the write path uses so
        // the preview's per-bin allocation matches what addLiquidity
        // will actually deposit. Diverging here would mislead the user.
        long totalLiquidity = req.amountX() + req.amountY();
        double[] weights = calculateDistributionWeights(req.strategy(), binRangeMin, binRangeMax, activeBinId);

        List<BinAllocation> allocations = new ArrayList<>();
        long totalDepositedX = 0;
        long totalDepositedY = 0;

        for (int binId = binRangeMin; binId <= binRangeMax; binId++) {
            int idx = binId - binRangeMin;
            long binLiquidity = (long) (totalLiquidity * weights[idx]);
            if (binLiquidity <= 0) continue;

            BigDecimal binPrice = BinMath.binPriceAtBin(basePrice, binStep, binId, activeBinId);
            long[] amounts = computeBinAmounts(pool, binId, binLiquidity, binPrice);
            long amountX = Math.min(amounts[0], req.amountX() - totalDepositedX);
            long amountY = Math.min(amounts[1], req.amountY() - totalDepositedY);

            if (amountX <= 0 && amountY <= 0) continue;

            allocations.add(new BinAllocation(binId, amountX, amountY, binLiquidity));
            totalDepositedX += amountX;
            totalDepositedY += amountY;
        }

        boolean inRange = binRangeMin <= activeBinId && activeBinId <= binRangeMax;

        long tvlBeforeX = pool.getTotalTvlX();
        long tvlBeforeY = pool.getTotalTvlY();
        long tvlAfterX = tvlBeforeX + totalDepositedX;
        long tvlAfterY = tvlBeforeY + totalDepositedY;

        // Same mixed-unit sum the rest of the engine uses
        // (Sprint 9-DS-r3 — canonical L-units is a future refactor).
        long totalAfter = tvlAfterX + tvlAfterY;
        double tvlSharePct = totalAfter > 0
                ? ((double) (totalDepositedX + totalDepositedY) / totalAfter) * 100.0
                : 0.0;

        // Heuristic: in-range adds don't shift the active price (you're
        // adding alongside existing liquidity at the current bin). Out-
        // of-range adds shift implied price toward the new liquidity by
        // a fraction proportional to how much you're adding vs existing
        // TVL — capped at 10000 bps (100%) for sanity.
        int priceImpactBps = 0;
        if (!inRange && totalAfter > 0) {
            // The midpoint of the user's range tells us which side of the
            // book is getting bid up. We don't model order-book depth
            // here — this is a rough "if you dumped X into a side bin,
            // the implied marginal price would move by ~yourShare × 10000".
            long depositTotal = totalDepositedX + totalDepositedY;
            long impactRaw = (depositTotal * 10_000L) / Math.max(totalAfter, 1L);
            priceImpactBps = (int) Math.min(impactRaw, 10_000L);
        }

        // Fee projection: pool's 24h volume × pool's base fee % × your share.
        // Pool's volume24h is denominated in Y units (rouble side for SRUB pairs);
        // the projection lands in the same unit. Caps at 0 when there's no
        // recent activity — don't show fake earnings on an idle pool.
        long estimatedFeesPerDayY = 0L;
        if (pool.getVolume24h() > 0 && tvlSharePct > 0 && inRange) {
            // Out-of-range positions earn no fees until price re-enters, so
            // the projection is meaningfully zero for them. The UI already
            // shows the warning; double-counting "earn rate = 0" here keeps
            // the number honest.
            double dailyFee = (double) pool.getVolume24h() * pool.getBaseFeeBps() / 10_000.0;
            estimatedFeesPerDayY = (long) (dailyFee * tvlSharePct / 100.0);
        }

        // Build warnings. Ordered by severity — UI renders top-down.
        List<String> warnings = new ArrayList<>();
        if (!inRange) {
            warnings.add(
                    "Ваш диапазон не включает активный бин — позиция простаивает пока цена не вернётся в диапазон.");
        }
        if (tvlSharePct > 10.0) {
            warnings.add(String.format(
                    "Доля TVL %.1f%% — большая концентрация, возможен высокий impermanent loss при движении цены.",
                    tvlSharePct));
        }
        if (allocations.isEmpty()) {
            warnings.add("При выбранных суммах и диапазоне ни в один бин не попадает ликвидность.");
        }
        long unusedX = req.amountX() - totalDepositedX;
        long unusedY = req.amountY() - totalDepositedY;
        if (unusedX > 0 || unusedY > 0) {
            warnings.add(String.format(
                    "Часть суммы не будет использована (X=%d, Y=%d) — расширьте диапазон или измените пропорции.",
                    unusedX, unusedY));
        }

        return new PreviewAddLiquidityResponse(
                tvlBeforeX, tvlBeforeY, tvlAfterX, tvlAfterY,
                tvlSharePct, inRange, priceImpactBps,
                totalDepositedX, totalDepositedY,
                allocations, estimatedFeesPerDayY, warnings);
    }

    /**
     * Sprint 11 G-22 — extracted side-assignment logic so preview +
     * write path share the same per-bin amount calculation. The
     * Sprint 9-DS-r3 bugfix lives here now (canonical DLMM: Y below
     * active, X above, both at active). Touching this without reading
     * that incident note will likely reintroduce the bug.
     *
     * <p>Returns {@code long[2] = {amountX, amountY}}.
     */
    private long[] computeBinAmounts(LiquidityPool pool, int binId, long binLiquidity, BigDecimal binPrice) {
        long amountX;
        long amountY;
        int activeBinId = pool.getActiveBinId();
        if (binId < activeBinId) {
            amountX = 0;
            amountY = binLiquidity;
        } else if (binId > activeBinId) {
            amountX = binPrice.compareTo(BigDecimal.ZERO) > 0
                    ? BigDecimal.valueOf(binLiquidity)
                            .divide(binPrice, 0, RoundingMode.FLOOR).longValue()
                    : 0;
            amountY = 0;
        } else {
            // Active bin: composition-factor split, clamped to [0,1]
            // (see Sprint 9-DS-r3 note in addLiquidity).
            BigDecimal cRaw = getOrDefaultCompositionFactor(pool.getId(), binId);
            BigDecimal c = cRaw.max(BigDecimal.ZERO).min(BigDecimal.ONE);
            amountY = BigDecimal.valueOf(binLiquidity).multiply(c, MC)
                    .setScale(0, RoundingMode.FLOOR).longValue();
            BigDecimal oneMinusC = BigDecimal.ONE.subtract(c, MC);
            amountX = binPrice.compareTo(BigDecimal.ZERO) > 0
                    ? BigDecimal.valueOf(binLiquidity).multiply(oneMinusC, MC)
                            .divide(binPrice, 0, RoundingMode.FLOOR).longValue()
                    : 0;
        }
        return new long[]{amountX, amountY};
    }

    @Transactional
    public RemoveLiquidityResponse removeLiquidity(RemoveLiquidityRequest req, UUID userId) {
        // 1. Find position
        LpPosition position = positionRepository.findById(req.positionId())
                .orElseThrow(() -> new PoolNotFoundException("Position not found: " + req.positionId()));

        if (!position.getUserId().equals(userId)) {
            throw new ForbiddenException("Position does not belong to user");
        }
        if (!position.isActive()) {
            throw new PoolNotActiveException("Position is already closed");
        }

        // Idempotency check
        if (req.idempotencyKey() != null && !req.idempotencyKey().isBlank()) {
            String redisKey = IDEMPOTENCY_PREFIX + "remove:" + req.idempotencyKey();
            Boolean wasAbsent = redisTemplate.opsForValue()
                    .setIfAbsent(redisKey, "processing", Duration.ofHours(24));
            if (Boolean.FALSE.equals(wasAbsent)) {
                throw new IdempotencyConflictException("Duplicate request: " + req.idempotencyKey());
            }
        }

        LiquidityPool pool = poolRepository.findById(position.getPoolId())
                .orElseThrow(() -> new PoolNotFoundException("Pool not found: " + position.getPoolId()));

        int percentageBps = req.percentageBps();
        if (percentageBps < 1 || percentageBps > 10_000) {
            throw new InvalidBinRangeException("percentageBps must be between 1 and 10000");
        }

        // 2. Calculate shares to remove
        List<PositionBin> positionBins = positionBinRepository.findByPositionId(position.getId());

        long totalWithdrawnX = 0;
        long totalWithdrawnY = 0;
        long totalClaimedFeeX = 0;
        long totalClaimedFeeY = 0;

        List<PositionBin> binsToRemove = new ArrayList<>();

        // 3. For each PositionBin
        for (PositionBin posBin : positionBins) {
            long binShareToRemove = posBin.getLiquidityShares() * percentageBps / 10_000;
            if (binShareToRemove <= 0) {
                continue;
            }

            PoolBin poolBin = poolBinRepository.findByPoolIdAndBinId(pool.getId(), posBin.getBinId())
                    .orElse(null);
            if (poolBin == null || poolBin.getLiquidity() <= 0) {
                continue;
            }

            // 3b. Calculate amounts to withdraw
            long amountX = poolBin.getReserveX() * binShareToRemove / poolBin.getLiquidity();
            long amountY = poolBin.getReserveY() * binShareToRemove / poolBin.getLiquidity();

            // 4. Calculate accrued fees (proportional to removed share)
            // feeGrowth is per-unit-of-liquidity, scaled by FEE_GROWTH_SCALE;
            // feeFromGrowth multiplies by the removed share and divides the
            // scale back out (clamping a negative delta to 0).
            long feeX = BinMath.feeFromGrowth(poolBin.getFeeGrowthX() - position.getLastFeeGrowthX(), binShareToRemove);
            long feeY = BinMath.feeFromGrowth(poolBin.getFeeGrowthY() - position.getLastFeeGrowthY(), binShareToRemove);
            feeX = Math.max(feeX, 0);
            feeY = Math.max(feeY, 0);

            // 3c. Update PoolBin
            poolBin.setLiquidity(poolBin.getLiquidity() - binShareToRemove);
            poolBin.setReserveX(poolBin.getReserveX() - amountX);
            poolBin.setReserveY(poolBin.getReserveY() - amountY);
            poolBin.setCompositionFactor(BinMath.compositionFactor(poolBin.getReserveY(), poolBin.getLiquidity()));
            poolBinRepository.save(poolBin);

            // 3d. Update PositionBin
            long remainingShares = posBin.getLiquidityShares() - binShareToRemove;
            if (remainingShares <= 0) {
                binsToRemove.add(posBin);
            } else {
                posBin.setLiquidityShares(remainingShares);
                positionBinRepository.save(posBin);
            }

            totalWithdrawnX += amountX;
            totalWithdrawnY += amountY;
            totalClaimedFeeX += feeX;
            totalClaimedFeeY += feeY;
        }

        // Remove empty position bins
        for (PositionBin pb : binsToRemove) {
            positionBinRepository.delete(pb);
        }

        // 5. Exit fee — Sprint 3 #3.4. Applies to withdrawn PRINCIPAL only
        // (not to fees earned, which already paid a share to the protocol
        // when accrued). Routes to pool's protocol fee accumulator —
        // claimable by admin treasury in the Sprint 3 #3.2 distribution
        // sweep. Computed before crediting the user.
        long exitFeeX = (totalWithdrawnX * lpExitFeeBps) / 10_000L;
        long exitFeeY = (totalWithdrawnY * lpExitFeeBps) / 10_000L;
        long creditX = (totalWithdrawnX - exitFeeX) + totalClaimedFeeX;
        long creditY = (totalWithdrawnY - exitFeeY) + totalClaimedFeeY;
        if (creditX > 0) {
            tokenServiceClient.creditBalance(userId, pool.getTokenXId(), creditX);
        }
        if (creditY > 0) {
            tokenServiceClient.creditBalance(userId, pool.getTokenYId(), creditY);
        }
        if (exitFeeX > 0 || exitFeeY > 0) {
            log.info("Exit fee charged: position={} feeX={} feeY={} ({}bps)",
                    position.getId(), exitFeeX, exitFeeY, lpExitFeeBps);
        }

        // 6. Update pool TVL — withdrawn amount left the pool entirely
        // (user got most, protocol kept exit fee on the pool's books).
        // Protocol fee stays on `liquidity_pools.total_fees_collected_*`
        // — those are the same accumulator already used by base swap fee.
        pool.setTotalTvlX(Math.max(0, pool.getTotalTvlX() - totalWithdrawnX));
        pool.setTotalTvlY(Math.max(0, pool.getTotalTvlY() - totalWithdrawnY));
        pool.setTotalFeesCollectedX(pool.getTotalFeesCollectedX() + exitFeeX);
        pool.setTotalFeesCollectedY(pool.getTotalFeesCollectedY() + exitFeeY);
        poolRepository.save(pool);

        // Update position shares and fee snapshots
        long removedShares = position.getTotalLiquidityShares() * percentageBps / 10_000;
        position.setTotalLiquidityShares(position.getTotalLiquidityShares() - removedShares);
        position.setUnclaimedFeeX(0);
        position.setUnclaimedFeeY(0);

        // Sprint 9-DS-r4 (P1-10) — scale cost-basis down proportionally
        // on partial removes so a 50% remove halves the basis as well.
        // Avoids a P&L jump from the same "remaining position" suddenly
        // being measured against the full original deposit.
        long remainingBps = 10_000 - percentageBps;
        position.setInitialDepositX(position.getInitialDepositX() * remainingBps / 10_000);
        position.setInitialDepositY(position.getInitialDepositY() * remainingBps / 10_000);

        // Update fee growth snapshot to current
        PoolBin activeBin = poolBinRepository.findByPoolIdAndBinId(pool.getId(), pool.getActiveBinId()).orElse(null);
        if (activeBin != null) {
            position.setLastFeeGrowthX(activeBin.getFeeGrowthX());
            position.setLastFeeGrowthY(activeBin.getFeeGrowthY());
        }

        // 7. Close position if 100%
        if (percentageBps == 10_000) {
            position.setActive(false);
            position.setClosedAt(LocalDateTime.now());
        }
        positionRepository.save(position);

        // 8. Outbox: durable LiquidityRemoved event
        outbox.append("position", position.getId().toString(), "LiquidityRemoved", POOL_EVENTS_TOPIC,
                new LiquidityRemovedEvent(pool.getId(), userId, position.getId(), totalWithdrawnX, totalWithdrawnY));

        log.info("Liquidity removed: pool={}, user={}, position={}, withdrawnX={}, withdrawnY={}, feeX={}, feeY={}",
                pool.getId(), userId, position.getId(), totalWithdrawnX, totalWithdrawnY, totalClaimedFeeX, totalClaimedFeeY);

        return new RemoveLiquidityResponse(position.getId(), totalWithdrawnX, totalWithdrawnY,
                totalClaimedFeeX, totalClaimedFeeY);
    }

    /**
     * Total open LP positions across all users — used by /admin/dashboard
     * to surface platform-wide liquidity engagement without paging through
     * every pool's position list.
     */
    public long countActivePositions() {
        return positionRepository.countByIsActiveTrue();
    }

    public List<PositionResponse> getUserPositions(UUID userId) {
        List<LpPosition> positions = positionRepository.findByUserIdAndIsActiveTrue(userId);
        List<PositionResponse> responses = new ArrayList<>();

        for (LpPosition pos : positions) {
            List<PositionBin> posBins = positionBinRepository.findByPositionId(pos.getId());
            long currentValueX = 0;
            long currentValueY = 0;
            long unclaimedFeeX = 0;
            long unclaimedFeeY = 0;

            List<BinAllocation> binAllocations = new ArrayList<>();

            for (PositionBin pb : posBins) {
                PoolBin poolBin = poolBinRepository.findByPoolIdAndBinId(pos.getPoolId(), pb.getBinId())
                        .orElse(null);
                if (poolBin == null || poolBin.getLiquidity() <= 0) {
                    binAllocations.add(new BinAllocation(pb.getBinId(), 0, 0, pb.getLiquidityShares()));
                    continue;
                }

                // Calculate current value proportional to shares
                long valueX = poolBin.getReserveX() * pb.getLiquidityShares() / poolBin.getLiquidity();
                long valueY = poolBin.getReserveY() * pb.getLiquidityShares() / poolBin.getLiquidity();
                currentValueX += valueX;
                currentValueY += valueY;

                // Calculate unclaimed fees
                long feeX = BinMath.feeFromGrowth(poolBin.getFeeGrowthX() - pos.getLastFeeGrowthX(), pb.getLiquidityShares());
                long feeY = BinMath.feeFromGrowth(poolBin.getFeeGrowthY() - pos.getLastFeeGrowthY(), pb.getLiquidityShares());
                unclaimedFeeX += Math.max(feeX, 0);
                unclaimedFeeY += Math.max(feeY, 0);

                binAllocations.add(new BinAllocation(pb.getBinId(), valueX, valueY, pb.getLiquidityShares()));
            }

            responses.add(new PositionResponse(
                    pos.getId(), pos.getUserId(), pos.getPoolId(),
                    pos.getBinRangeMin(), pos.getBinRangeMax(), pos.getStrategy(),
                    pos.getTotalLiquidityShares(), currentValueX, currentValueY,
                    pos.getUnclaimedFeeX() + unclaimedFeeX,
                    pos.getUnclaimedFeeY() + unclaimedFeeY,
                    // Sprint 9-DS-r4 (P1-10) — cost-basis surface
                    // for the PositionsPage P&L column. 0 for any
                    // legacy position opened before the schema
                    // migration; new positions carry real values.
                    pos.getInitialDepositX(),
                    pos.getInitialDepositY(),
                    pos.isActive(), pos.getCreatedAt(), pos.getClosedAt(),
                    binAllocations));
        }

        return responses;
    }

    // ── Strategy Weight Calculation ─────────────────────────────

    private double[] calculateDistributionWeights(LiquidityStrategy strategy, int binMin, int binMax, int activeBinId) {
        int numBins = binMax - binMin + 1;
        double[] weights = new double[numBins];

        switch (strategy) {
            case SPOT -> {
                // Uniform distribution
                double uniformWeight = 1.0 / numBins;
                for (int i = 0; i < numBins; i++) {
                    weights[i] = uniformWeight;
                }
            }
            case CURVE -> {
                // Gaussian distribution centered on activeBin
                double sigma = (double) (binMax - binMin) / 6.0;
                if (sigma <= 0) sigma = 1.0;
                double sumRaw = 0;
                for (int i = 0; i < numBins; i++) {
                    int binId = binMin + i;
                    double dist = binId - activeBinId;
                    weights[i] = Math.exp(-(dist * dist) / (2.0 * sigma * sigma));
                    sumRaw += weights[i];
                }
                // Normalize
                if (sumRaw > 0) {
                    for (int i = 0; i < numBins; i++) {
                        weights[i] /= sumRaw;
                    }
                }
            }
            case BID_ASK -> {
                // Edge-weighted: more liquidity at the edges, less in center
                double maxDistance = Math.max(Math.abs(binMax - activeBinId), Math.abs(binMin - activeBinId));
                if (maxDistance <= 0) maxDistance = 1.0;
                double sumRaw = 0;
                for (int i = 0; i < numBins; i++) {
                    int binId = binMin + i;
                    double dist = Math.abs(binId - activeBinId);
                    weights[i] = dist / maxDistance;
                    // Ensure minimum weight so all bins get some liquidity
                    weights[i] = Math.max(weights[i], 0.05);
                    sumRaw += weights[i];
                }
                // Normalize
                if (sumRaw > 0) {
                    for (int i = 0; i < numBins; i++) {
                        weights[i] /= sumRaw;
                    }
                }
            }
        }

        return weights;
    }

    private BigDecimal getOrDefaultCompositionFactor(UUID poolId, int binId) {
        return poolBinRepository.findByPoolIdAndBinId(poolId, binId)
                .map(PoolBin::getCompositionFactor)
                .orElse(new BigDecimal("0.5"));
    }
}
