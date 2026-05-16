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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
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
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;

    public LiquidityService(LiquidityPoolRepository poolRepository,
                            PoolBinRepository poolBinRepository,
                            LpPositionRepository positionRepository,
                            PositionBinRepository positionBinRepository,
                            TokenServiceClient tokenServiceClient,
                            UserServiceClient userServiceClient,
                            KafkaTemplate<String, Object> kafkaTemplate,
                            StringRedisTemplate redisTemplate) {
        this.poolRepository = poolRepository;
        this.poolBinRepository = poolBinRepository;
        this.positionRepository = positionRepository;
        this.positionBinRepository = positionBinRepository;
        this.tokenServiceClient = tokenServiceClient;
        this.userServiceClient = userServiceClient;
        this.kafkaTemplate = kafkaTemplate;
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

            BigDecimal binPrice = BinMath.binPrice(basePrice, binStep, binId);

            long amountX = 0;
            long amountY = 0;

            if (binId < activeBinId) {
                // Below active bin: only token X
                // amountX = liquidity / price
                amountX = binPrice.compareTo(BigDecimal.ZERO) > 0
                        ? BigDecimal.valueOf(binLiquidity)
                                .divide(binPrice, 0, RoundingMode.FLOOR).longValue()
                        : 0;
                amountY = 0;
            } else if (binId > activeBinId) {
                // Above active bin: only token Y
                amountX = 0;
                amountY = binLiquidity;
            } else {
                // Active bin: both tokens, based on composition factor
                BigDecimal c = getOrDefaultCompositionFactor(pool.getId(), binId);
                // amountY = liquidity * c
                amountY = BigDecimal.valueOf(binLiquidity).multiply(c, MC)
                        .setScale(0, RoundingMode.FLOOR).longValue();
                // amountX = liquidity * (1 - c) / price
                BigDecimal oneMinusC = BigDecimal.ONE.subtract(c, MC);
                amountX = binPrice.compareTo(BigDecimal.ZERO) > 0
                        ? BigDecimal.valueOf(binLiquidity).multiply(oneMinusC, MC)
                                .divide(binPrice, 0, RoundingMode.FLOOR).longValue()
                        : 0;
            }

            // Cap amounts by what user provided
            amountX = Math.min(amountX, req.amountX() - totalDepositedX);
            amountY = Math.min(amountY, req.amountY() - totalDepositedY);

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
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .build();
        positionRepository.save(position);

        // 8. Kafka event
        kafkaTemplate.send(POOL_EVENTS_TOPIC, pool.getId().toString(),
                new LiquidityAddedEvent(pool.getId(), userId, positionId, totalDepositedX, totalDepositedY));

        log.info("Liquidity added: pool={}, user={}, position={}, depositedX={}, depositedY={}, shares={}",
                pool.getId(), userId, positionId, totalDepositedX, totalDepositedY, totalShares);

        // 9. Return response
        return new AddLiquidityResponse(positionId, pool.getId(), binRangeMin, binRangeMax,
                req.strategy(), totalDepositedX, totalDepositedY, totalShares, allocations);
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
            long feeX = (poolBin.getFeeGrowthX() - position.getLastFeeGrowthX()) * binShareToRemove;
            long feeY = (poolBin.getFeeGrowthY() - position.getLastFeeGrowthY()) * binShareToRemove;
            // feeGrowth is per-unit-of-liquidity, so feeX already accounts for shares
            // Clamp negative fees to 0
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

        // 5. Credit amounts + fees to user balance
        long creditX = totalWithdrawnX + totalClaimedFeeX;
        long creditY = totalWithdrawnY + totalClaimedFeeY;
        if (creditX > 0) {
            tokenServiceClient.creditBalance(userId, pool.getTokenXId(), creditX);
        }
        if (creditY > 0) {
            tokenServiceClient.creditBalance(userId, pool.getTokenYId(), creditY);
        }

        // 6. Update pool TVL
        pool.setTotalTvlX(Math.max(0, pool.getTotalTvlX() - totalWithdrawnX));
        pool.setTotalTvlY(Math.max(0, pool.getTotalTvlY() - totalWithdrawnY));
        poolRepository.save(pool);

        // Update position shares and fee snapshots
        long removedShares = position.getTotalLiquidityShares() * percentageBps / 10_000;
        position.setTotalLiquidityShares(position.getTotalLiquidityShares() - removedShares);
        position.setUnclaimedFeeX(0);
        position.setUnclaimedFeeY(0);

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

        // 8. Kafka event
        kafkaTemplate.send(POOL_EVENTS_TOPIC, pool.getId().toString(),
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
                long feeX = (poolBin.getFeeGrowthX() - pos.getLastFeeGrowthX()) * pb.getLiquidityShares();
                long feeY = (poolBin.getFeeGrowthY() - pos.getLastFeeGrowthY()) * pb.getLiquidityShares();
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
