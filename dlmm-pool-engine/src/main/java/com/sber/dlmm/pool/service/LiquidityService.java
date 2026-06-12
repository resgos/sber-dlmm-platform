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

/**
 * Manages LP positions: depositing liquidity across a bin range per a chosen
 * strategy, removing it (with fee claim + exit fee), previewing a deposit, and
 * reading a user's positions with live valuation. The counterpart to
 * {@link SwapService} — together they own pool bin reserves and must both keep
 * the F-12 invariant {@code liquidity = reserveX·price + reserveY} per bin.
 *
 * <h2>Deposit model</h2>
 * A request supplies amountX/amountY, a bin range, and a
 * {@link LiquidityStrategy} (SPOT/CURVE/BID_ASK). The strategy yields per-bin
 * weights; each bin's target liquidity is split into X and Y by the canonical
 * DLMM side rule: bins <b>below</b> the active price hold Y, bins <b>above</b>
 * hold X, and the active bin splits by its composition factor (clamped to
 * [0,1]). Bin prices are computed via {@link BinMath#binPriceAtBin} relative to
 * {@code activeBinId} — feeding a raw absolute bin id to the plain
 * {@code binPrice} overflowed at the 2^23 anchor and silently zeroed the X side
 * (the Sprint 9-DS-r3 incident; the helper now encapsulates the offset so a
 * careless edit can't reintroduce it).
 *
 * <h2>Money-safety &amp; idempotency</h2>
 * Amounts are raw ×10⁴ units; all splits FLOOR. Token deduct/credit go through
 * {@link TokenServiceClient}; the durable {@code LiquidityAdded}/
 * {@code LiquidityRemoved} events go through the transactional
 * {@link OutboxService} in the same transaction as the bin/position writes.
 * Client idempotency keys are claimed via Redis SETNX (add and remove use
 * distinct key prefixes). KYC is fail-closed and add-liquidity is gated by the
 * 115-ФЗ self-restriction; removing/closing a position is deliberately NOT
 * gated (restricted users can always exit).
 *
 * <h2>Fees on remove</h2>
 * Accrued LP fees are derived from per-unit {@code feeGrowth} deltas since the
 * position's snapshot (clamped ≥ 0) and paid on the removed share. A separate
 * {@code lp-exit-bps} exit fee is charged on withdrawn PRINCIPAL only and routed
 * to the pool's protocol-fee accumulator. Partial removes scale the cost-basis
 * snapshot down proportionally so the P&amp;L column doesn't jump.
 */
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

    /**
     * @param poolRepository        pool rows (active bin, base price, TVL +
     *                              protocol-fee accumulators)
     * @param poolBinRepository     per-bin reserves/price/fee-growth read &amp;
     *                              written on add/remove
     * @param positionRepository    LP position rows (shares, fee snapshot,
     *                              cost basis, active flag)
     * @param positionBinRepository per-bin share rows linking a position to bins
     * @param tokenServiceClient    token-active validation + balance deduct/credit
     * @param userServiceClient     fail-closed KYC + 115-ФЗ self-restriction gate
     * @param outbox                transactional outbox for liquidity events
     * @param redisTemplate         Redis backing the idempotency-key claim
     */
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

    /**
     * Deposit liquidity across a bin range, creating a new LP position and
     * crediting per-bin shares.
     *
     * <p>Validates pool ACTIVE, both tokens active, caller KYC-verified
     * (fail-closed) and not 115-ФЗ self-restricted, claims the idempotency key,
     * and checks the bin range (1..1000 bins, at least one positive side —
     * single-sided is allowed for Meteora parity). It then distributes the
     * combined liquidity over the range by the strategy weights, computes each
     * bin's X/Y split (canonical side rule, see {@link #computeBinAmounts}),
     * upserts each {@link PoolBin} (reserves + composition factor) and records a
     * {@link PositionBin} share row. Only the amounts that actually fit are
     * deducted from the user; pool TVL is bumped by the deposited totals; the
     * position snapshots the active bin's fee growth and the initial deposit as
     * cost basis. A {@code LiquidityAdded} event is appended to the outbox in
     * this transaction.
     *
     * @param req    deposit request (pool, amounts, bin range, strategy,
     *               idempotency key)
     * @param userId authenticated depositor, debited the deposited amounts
     * @return the created position with its per-bin allocations and deposited totals
     * @throws PoolNotFoundException        if the pool does not exist
     * @throws PoolNotActiveException       if the pool or either token is not active
     * @throws ForbiddenException           if the caller is not KYC-verified
     * @throws com.sber.dlmm.common.exception.UserSelfRestrictedException if 115-ФЗ
     *                                      self-restriction is active
     * @throws IdempotencyConflictException if the idempotency key is already in flight
     * @throws InvalidBinRangeException     if the range is invalid, both sides are
     *                                      ≤ 0, or nothing could be allocated
     */
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

        // Sprint 16 (Meteora parity) — single-sided liquidity is allowed (one side
        // may be 0), but at least one side must be positive.
        if (req.amountX() <= 0 && req.amountY() <= 0) {
            throw new InvalidBinRangeException("Provide a positive amountX or amountY");
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

            // Create PositionBin — stamp THIS bin's current fee-growth as the
            // per-bin checkpoint (Meteora model). The bin's owed fee is later
            // feeFromGrowth(poolBin.feeGrowth - checkpoint, shares); entering now
            // means zero owed until the next swap moves this bin's growth.
            //
            // INVARIANT: addLiquidity always creates a NEW position (positionId is a
            // fresh UUID, see top of method), so this position has no pre-existing
            // shares in this bin — the checkpoint is a clean entry snapshot. If a
            // top-up path is ever added (adding to an EXISTING position's bin), it
            // MUST first settle the existing shares' owed fee
            // (feeFromGrowth(growth - oldCheckpoint, oldShares) -> unclaimed_fee) and
            // only then bump shares + reset the checkpoint — otherwise the new shares
            // would retroactively claim fees accrued before they were deposited.
            PositionBin posBin = PositionBin.builder()
                    .positionId(positionId)
                    .binId(binId)
                    .liquidityShares(binLiquidity)
                    .feeGrowthCheckpointX(poolBin.getFeeGrowthX())
                    .feeGrowthCheckpointY(poolBin.getFeeGrowthY())
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

        // 7. Create LpPosition.
        // Fee tracking is now PER BIN — see PositionBin.feeGrowthCheckpointX/Y stamped
        // in the loop above. The position-wide lastFeeGrowthX/Y snapshot is DEPRECATED
        // and kept at 0 only because the column still exists; no money path reads it
        // anymore (getUserPositions, removeLiquidity and the fee claim all use the
        // per-bin checkpoint). Do NOT reintroduce a single-snapshot computation here.
        long lastFeeGrowthX = 0;
        long lastFeeGrowthY = 0;

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
     *
     * @param req the same add-liquidity request the user is composing
     * @return projected TVL before/after, TVL share, in-range flag, rough price
     *         impact, per-bin allocations, fee-per-day estimate and human-readable
     *         warnings
     * @throws PoolNotFoundException    if the pool does not exist
     * @throws PoolNotActiveException   if the pool is not active
     * @throws InvalidBinRangeException if the bin range is malformed
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
     * <p>Side rule: below the active bin → all Y; above → all X (= liquidity /
     * price, floored); at the active bin → composition-factor split (Y = L·c,
     * X = L·(1−c) / price), with {@code c} clamped to [0,1].
     *
     * @param pool         pool (supplies the active bin id)
     * @param binId        bin being filled
     * @param binLiquidity target liquidity (L) for this bin
     * @param binPrice     this bin's price (Y per X)
     * @return {@code long[2] = {amountX, amountY}}
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

    /**
     * Withdraw a percentage of an LP position, paying out principal + accrued
     * fees (minus an exit fee) and closing the position on a full withdrawal.
     *
     * <p>Verifies ownership and that the position is open, claims the (remove-
     * scoped) idempotency key, and validates {@code percentageBps} in
     * [1..10000]. For each position bin it removes the proportional share,
     * returns the proportional reserveX/reserveY, and computes accrued fees from
     * the per-unit fee-growth delta since the position's snapshot (clamped ≥ 0);
     * empty bin-share rows are deleted. An exit fee ({@code lp-exit-bps}) is
     * skimmed from withdrawn PRINCIPAL only (not from fees, which already paid
     * the protocol on accrual) and routed to the pool's protocol-fee
     * accumulator; the user is credited principal − exit fee + fees. Pool TVL is
     * reduced by the withdrawn principal, the position's shares and cost basis
     * are scaled down by the removed fraction, its fee snapshot is refreshed,
     * and at 100% it is marked closed. A {@code LiquidityRemoved} event is
     * appended to the outbox in this transaction.
     *
     * @param req    remove request (position id, percentageBps, idempotency key)
     * @param userId authenticated owner, credited the proceeds
     * @return withdrawn X/Y and claimed fee X/Y
     * @throws PoolNotFoundException        if the position or its pool is missing
     * @throws ForbiddenException           if the position is not the caller's
     * @throws PoolNotActiveException       if the position is already closed
     * @throws IdempotencyConflictException if the idempotency key is already in flight
     * @throws InvalidBinRangeException     if {@code percentageBps} is out of [1..10000]
     */
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

            // 3b. Calculate amounts to withdraw. mulDiv: the raw long product
            // reserve·shares overflows on ×10⁴ demo magnitudes (≈2.8e23) and wrapped
            // into corrupt (even negative) withdraw amounts — audit B6.
            long amountX = BinMath.mulDiv(poolBin.getReserveX(), binShareToRemove, poolBin.getLiquidity());
            long amountY = BinMath.mulDiv(poolBin.getReserveY(), binShareToRemove, poolBin.getLiquidity());

            // 4. Settle ALL accrued fees for this bin (Meteora: a remove claims the
            // position's full pending fees, not just the removed proportion). Compute
            // against this bin's PER-BIN checkpoint on the FULL shares held here, then
            // advance the checkpoint below so they can't be re-counted. feeGrowth is
            // per-unit-of-liquidity (FEE_GROWTH_SCALE); feeFromGrowth multiplies by
            // shares and divides the scale back out, clamping a non-positive delta to 0.
            long feeX = BinMath.feeFromGrowth(poolBin.getFeeGrowthX() - posBin.getFeeGrowthCheckpointX(), posBin.getLiquidityShares());
            long feeY = BinMath.feeFromGrowth(poolBin.getFeeGrowthY() - posBin.getFeeGrowthCheckpointY(), posBin.getLiquidityShares());
            feeX = Math.max(feeX, 0);
            feeY = Math.max(feeY, 0);

            // 3c. Update PoolBin
            poolBin.setLiquidity(poolBin.getLiquidity() - binShareToRemove);
            poolBin.setReserveX(poolBin.getReserveX() - amountX);
            poolBin.setReserveY(poolBin.getReserveY() - amountY);
            poolBin.setCompositionFactor(BinMath.compositionFactor(poolBin.getReserveY(), poolBin.getLiquidity()));
            poolBinRepository.save(poolBin);

            // 3d. Update PositionBin. The full accrued fee was just paid above, so
            // advance this bin's checkpoint to its current growth — the surviving
            // shares show owed=0 immediately after and accrue afresh from here. (A
            // fully-removed bin is deleted, so its checkpoint is moot.)
            long remainingShares = posBin.getLiquidityShares() - binShareToRemove;
            if (remainingShares <= 0) {
                binsToRemove.add(posBin);
            } else {
                posBin.setLiquidityShares(remainingShares);
                posBin.setFeeGrowthCheckpointX(poolBin.getFeeGrowthX());
                posBin.setFeeGrowthCheckpointY(poolBin.getFeeGrowthY());
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

        // (Fee-growth checkpoints are advanced PER BIN inside the loop above; the
        // deprecated position-wide lastFeeGrowth snapshot is no longer maintained.)

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
     *
     * @return count of currently-active LP positions
     */
    public long countActivePositions() {
        return positionRepository.countByIsActiveTrue();
    }

    /**
     * List a user's open positions with each one's CURRENT value and unclaimed
     * fees recomputed live from pool state.
     *
     * <p>For every position bin it values the share against the bin's current
     * reserves ({@code reserve · shares / liquidity}) and computes pending fees
     * from the fee-growth delta since the position's snapshot (clamped ≥ 0),
     * summing per position. Bins whose pool bin has been drained contribute zero
     * value but are still listed (with their share) so the breakdown stays
     * complete. The cost-basis fields are surfaced for the P&amp;L column (0 for
     * legacy positions created before that snapshot existed).
     *
     * @param userId user whose active positions to return
     * @return one response per active position, with live valuation, unclaimed
     *         fees, cost basis and per-bin allocations
     */
    public List<PositionResponse> getUserPositions(UUID userId) {
        List<LpPosition> positions = positionRepository.findByUserIdAndIsActiveTrue(userId);
        List<PositionResponse> responses = new ArrayList<>();

        // Audit B3 — resolve each pool's token symbols once (batched) so the
        // response is self-describing; the UI no longer has to join the pool
        // catalogue to label a position. Best-effort: any miss leaves the symbol
        // null and the UI falls back to its catalogue join.
        java.util.Map<UUID, LiquidityPool> poolById = new java.util.HashMap<>();
        for (LiquidityPool p : poolRepository.findAllById(
                positions.stream().map(LpPosition::getPoolId).distinct().toList())) {
            poolById.put(p.getId(), p);
        }
        java.util.Map<UUID, String> symbolByToken = new java.util.HashMap<>();
        try {
            java.util.Set<UUID> tokenIds = new java.util.HashSet<>();
            for (LiquidityPool p : poolById.values()) {
                tokenIds.add(p.getTokenXId());
                tokenIds.add(p.getTokenYId());
            }
            if (!tokenIds.isEmpty()) {
                tokenServiceClient.getTokensByIds(tokenIds).forEach((id, info) -> {
                    if (info != null) symbolByToken.put(id, info.symbol());
                });
            }
        } catch (RuntimeException ignored) {
            // best-effort: symbols stay null, UI falls back to its catalogue join
        }

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

                // Calculate current value proportional to shares. mulDiv: raw long
                // multiply overflowed on ×10⁴ demo magnitudes and showed NEGATIVE
                // position values in the UI (audit B6, reproduced live 2026-06-12).
                long valueX = BinMath.mulDiv(poolBin.getReserveX(), pb.getLiquidityShares(), poolBin.getLiquidity());
                long valueY = BinMath.mulDiv(poolBin.getReserveY(), pb.getLiquidityShares(), poolBin.getLiquidity());
                currentValueX += valueX;
                currentValueY += valueY;

                // Calculate unclaimed fees against THIS bin's per-bin checkpoint
                // (Meteora model): owed = feeFromGrowth(bin growth - the position's
                // checkpoint in this bin, the position's shares in this bin). Each bin
                // is measured from where the position entered it — no single
                // position-wide snapshot (which under/over-counted bins away from the
                // active price). feeFromGrowth floors and clamps a non-positive delta
                // to 0; the extra Math.max is belt-and-suspenders.
                long feeX = BinMath.feeFromGrowth(poolBin.getFeeGrowthX() - pb.getFeeGrowthCheckpointX(), pb.getLiquidityShares());
                long feeY = BinMath.feeFromGrowth(poolBin.getFeeGrowthY() - pb.getFeeGrowthCheckpointY(), pb.getLiquidityShares());
                unclaimedFeeX += Math.max(feeX, 0);
                unclaimedFeeY += Math.max(feeY, 0);

                binAllocations.add(new BinAllocation(pb.getBinId(), valueX, valueY, pb.getLiquidityShares()));
            }

            LiquidityPool posPool = poolById.get(pos.getPoolId());
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
                    binAllocations,
                    // Audit B3 — self-describing token symbols (null on lookup miss).
                    posPool != null ? symbolByToken.get(posPool.getTokenXId()) : null,
                    posPool != null ? symbolByToken.get(posPool.getTokenYId()) : null));
        }

        return responses;
    }

    // ── Strategy Weight Calculation ─────────────────────────────

    /**
     * Compute per-bin liquidity weights (summing to ~1) for a deposit strategy.
     *
     * <ul>
     *   <li>{@code SPOT} — uniform: every bin gets {@code 1/numBins}.</li>
     *   <li>{@code CURVE} — Gaussian centred on the active bin ({@code sigma =
     *       range/6}), concentrating liquidity near the current price, then
     *       normalised.</li>
     *   <li>{@code BID_ASK} — edge-weighted: weight grows with distance from the
     *       active bin (floored at 0.05 so no bin is empty), then normalised —
     *       the inverse profile, deeper at the range edges.</li>
     * </ul>
     *
     * @param strategy    chosen liquidity-shape strategy
     * @param binMin      inclusive lower bin id of the range
     * @param binMax      inclusive upper bin id of the range
     * @param activeBinId pool's active bin, the centre for CURVE/BID_ASK
     * @return a weight per bin in {@code [binMin, binMax]}, normalised to sum ≈ 1
     */
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

    /**
     * Composition factor for an existing bin, defaulting to {@code 0.5} (an even
     * 50/50 X/Y split) when the bin doesn't exist yet — so a first deposit into
     * the active bin starts balanced rather than all on one side.
     *
     * @param poolId pool owning the bin
     * @param binId  bin to read the composition factor of
     * @return the bin's composition factor, or {@code 0.5} if the bin is absent
     */
    private BigDecimal getOrDefaultCompositionFactor(UUID poolId, int binId) {
        return poolBinRepository.findByPoolIdAndBinId(poolId, binId)
                .map(PoolBin::getCompositionFactor)
                .orElse(new BigDecimal("0.5"));
    }
}
