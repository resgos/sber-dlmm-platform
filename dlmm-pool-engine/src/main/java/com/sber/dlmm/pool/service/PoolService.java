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
import com.sber.dlmm.pool.outbox.OutboxService;
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

@Service
public class PoolService {

    private static final Logger log = LoggerFactory.getLogger(PoolService.class);
    private static final String POOL_EVENTS_TOPIC = "pool-events";

    private final LiquidityPoolRepository poolRepository;
    private final PoolBinRepository poolBinRepository;
    private final TokenServiceClient tokenServiceClient;
    private final OutboxService outbox;

    public PoolService(LiquidityPoolRepository poolRepository,
                       PoolBinRepository poolBinRepository,
                       TokenServiceClient tokenServiceClient,
                       OutboxService outbox) {
        this.poolRepository = poolRepository;
        this.poolBinRepository = poolBinRepository;
        this.tokenServiceClient = tokenServiceClient;
        this.outbox = outbox;
    }

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

    @Transactional
    public PoolResponse pausePool(UUID poolId) {
        LiquidityPool pool = poolRepository.findById(poolId)
                .orElseThrow(() -> new PoolNotFoundException("Pool not found: " + poolId));
        pool.setStatus(PoolStatus.PAUSED);
        poolRepository.save(pool);
        log.info("Pool paused: {}", poolId);
        return toPoolResponse(pool, null, null);
    }

    @Transactional
    public PoolResponse emergencyShutdown(UUID poolId) {
        LiquidityPool pool = poolRepository.findById(poolId)
                .orElseThrow(() -> new PoolNotFoundException("Pool not found: " + poolId));
        pool.setStatus(PoolStatus.EMERGENCY_SHUTDOWN);
        poolRepository.save(pool);
        log.warn("Pool emergency shutdown: {}", poolId);
        return toPoolResponse(pool, null, null);
    }

    @Transactional
    public PoolResponse resumePool(UUID poolId) {
        LiquidityPool pool = poolRepository.findById(poolId)
                .orElseThrow(() -> new PoolNotFoundException("Pool not found: " + poolId));
        pool.setStatus(PoolStatus.ACTIVE);
        poolRepository.save(pool);
        log.info("Pool resumed: {}", poolId);
        return toPoolResponse(pool, null, null);
    }

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

    private int calculateDynamicFee(LiquidityPool pool) {
        long vaSquared = (long) pool.getVolatilityAccumulator() * pool.getVolatilityAccumulator();
        long variableFeeBps = vaSquared * pool.getBinStep() / 10_000_000_000L;
        return (int) (pool.getBaseFeeBps() + variableFeeBps);
    }

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
                pool.getVolume24h(), apy, pool.getStatus(), pool.getCreatedAt());
    }

    private PoolResponse toPoolResponseWithApy(LiquidityPool pool, String tokenXSymbol,
                                                 String tokenYSymbol, BigDecimal apy) {
        // See toPoolResponse(...) comment above for why basePrice is used directly
        // instead of BinMath.binPrice(base, step, activeBinId).
        BigDecimal currentPrice = pool.getBasePrice();
        return new PoolResponse(pool.getId(), pool.getTokenXId(), pool.getTokenYId(),
                tokenXSymbol, tokenYSymbol, pool.getBinStep(), pool.getBaseFeeBps(),
                pool.getActiveBinId(), currentPrice, pool.getTotalTvlX(), pool.getTotalTvlY(),
                pool.getVolume24h(), apy, pool.getStatus(), pool.getCreatedAt());
    }
}
