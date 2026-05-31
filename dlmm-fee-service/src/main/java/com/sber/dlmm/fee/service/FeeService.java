package com.sber.dlmm.fee.service;

import com.sber.dlmm.common.dto.PageResponse;
import com.sber.dlmm.common.exception.IdempotencyConflictException;
import com.sber.dlmm.fee.dto.ClaimFeesRequest;
import com.sber.dlmm.fee.dto.ClaimFeesResponse;
import com.sber.dlmm.fee.dto.FeeAccrualDto;
import com.sber.dlmm.fee.dto.FeesSummaryResponse;
import com.sber.dlmm.fee.dto.PoolFeeSummary;
import com.sber.dlmm.fee.client.TokenServiceClient;
import com.sber.dlmm.fee.entity.FeeAccrual;
import com.sber.dlmm.fee.event.FeeClaimedEvent;
import com.sber.dlmm.fee.repository.FeeAccrualRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class FeeService {

    private static final String FEE_EVENTS_TOPIC = "fee-events";

    private final FeeAccrualRepository feeAccrualRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    // Sprint 8 C-10 — was an inline WebClient; extracted to TokenServiceClient
    // so @CircuitBreaker + @Retry can be applied (AOP needs a public method on
    // a separate bean — same-class private calls aren't intercepted).
    private final TokenServiceClient tokenServiceClient;
    // Sprint 16 (Meteora parity, quote-only fees) — read the pool's X/Y token ids
    // + price straight from the shared DB to convert an X-fee into the quote token.
    private final JdbcTemplate jdbcTemplate;

    private static final MathContext MC = MathContext.DECIMAL128;

    private final Set<String> processedIdempotencyKeys = ConcurrentHashMap.newKeySet();

    @Transactional(readOnly = true)
    public FeesSummaryResponse getUserFeesSummary(UUID userId) {
        log.debug("Getting fee summary for user: {}", userId);

        List<FeeAccrual> allAccruals = feeAccrualRepository.findByUserId(userId);

        Map<UUID, List<FeeAccrual>> byPool = allAccruals.stream()
                .collect(Collectors.groupingBy(FeeAccrual::getPoolId, LinkedHashMap::new, Collectors.toList()));

        List<PoolFeeSummary> poolSummaries = new ArrayList<>();
        long totalUnclaimedX = 0;
        long totalUnclaimedY = 0;
        long totalEarnedX = 0;
        long totalEarnedY = 0;

        for (Map.Entry<UUID, List<FeeAccrual>> entry : byPool.entrySet()) {
            UUID poolId = entry.getKey();
            List<FeeAccrual> poolAccruals = entry.getValue();

            Map<UUID, long[]> tokenTotals = new HashMap<>();
            for (FeeAccrual accrual : poolAccruals) {
                long[] totals = tokenTotals.computeIfAbsent(accrual.getTokenId(), k -> new long[2]);
                totals[0] += accrual.getAmount();
                if (!accrual.isClaimed()) {
                    totals[1] += accrual.getAmount();
                }
            }

            List<UUID> tokenIds = new ArrayList<>(tokenTotals.keySet());
            UUID tokenXId = tokenIds.isEmpty() ? null : tokenIds.get(0);
            UUID tokenYId = tokenIds.size() > 1 ? tokenIds.get(1) : null;

            long totalEarnedFeeX = tokenXId != null ? tokenTotals.get(tokenXId)[0] : 0;
            long totalEarnedFeeY = tokenYId != null ? tokenTotals.get(tokenYId)[0] : 0;
            long unclaimedFeeX = tokenXId != null ? tokenTotals.get(tokenXId)[1] : 0;
            long unclaimedFeeY = tokenYId != null ? tokenTotals.get(tokenYId)[1] : 0;

            totalUnclaimedX += unclaimedFeeX;
            totalUnclaimedY += unclaimedFeeY;
            totalEarnedX += totalEarnedFeeX;
            totalEarnedY += totalEarnedFeeY;

            poolSummaries.add(new PoolFeeSummary(
                    poolId,
                    poolId.toString(),
                    unclaimedFeeX,
                    unclaimedFeeY,
                    totalEarnedFeeX,
                    totalEarnedFeeY,
                    BigDecimal.ZERO
            ));
        }

        long totalUnclaimed = totalUnclaimedX + totalUnclaimedY;
        long totalClaimed = (totalEarnedX + totalEarnedY) - totalUnclaimed;

        return new FeesSummaryResponse(userId, poolSummaries, totalUnclaimedX, totalUnclaimedY,
                totalEarnedX, totalEarnedY, totalClaimed, totalUnclaimed);
    }

    @Transactional
    public ClaimFeesResponse claimFees(ClaimFeesRequest request, UUID userId) {
        return claimFees(request, userId, false);
    }

    /**
     * Sprint 16 (Meteora parity) — {@code quoteOnly} consolidates the claim into
     * the pool's quote token (Y): the X-fee is converted at the pool's current
     * price and credited as Y, so the LP receives a single token instead of two.
     * If the pool can't be read, it falls back to the standard split credit so
     * fees are never lost.
     */
    @Transactional
    public ClaimFeesResponse claimFees(ClaimFeesRequest request, UUID userId, boolean quoteOnly) {
        log.info("Claiming fees for position: {} by user: {} (quoteOnly={})", request.positionId(), userId, quoteOnly);

        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            if (!processedIdempotencyKeys.add(request.idempotencyKey())) {
                throw new IdempotencyConflictException(
                        "Request with idempotency key '" + request.idempotencyKey() + "' has already been processed");
            }
        }

        List<FeeAccrual> unclaimedAccruals = feeAccrualRepository.findByPositionId(request.positionId()).stream()
                .filter(a -> !a.isClaimed())
                .filter(a -> a.getUserId().equals(userId))
                .toList();

        if (unclaimedAccruals.isEmpty()) {
            return new ClaimFeesResponse(request.positionId(), 0, 0, null, null);
        }

        LocalDateTime now = LocalDateTime.now();
        for (FeeAccrual accrual : unclaimedAccruals) {
            accrual.setClaimed(true);
            accrual.setClaimedAt(now);
        }
        feeAccrualRepository.saveAll(unclaimedAccruals);

        Map<UUID, Long> claimedByToken = unclaimedAccruals.stream()
                .collect(Collectors.groupingBy(FeeAccrual::getTokenId, Collectors.summingLong(FeeAccrual::getAmount)));

        UUID poolId = unclaimedAccruals.get(0).getPoolId();

        // Quote-only — convert the X-fee to the quote token (Y) and credit one token.
        PoolXY pool = quoteOnly ? lookupPoolXY(poolId) : null;
        if (quoteOnly && pool != null) {
            try {
                long claimedX = claimedByToken.getOrDefault(pool.tokenXId(), 0L);
                long claimedY = claimedByToken.getOrDefault(pool.tokenYId(), 0L);
                long totalY = quoteOnlyTotalY(claimedX, claimedY, pool.price());
                if (totalY > 0) {
                    tokenServiceClient.credit(userId, pool.tokenYId(), totalY);
                }
                kafkaTemplate.send(FEE_EVENTS_TOPIC, request.positionId().toString(),
                        new FeeClaimedEvent(request.positionId(), userId, poolId, 0, totalY, null, pool.tokenYId(), now));
                log.info("Claimed fees quote-only for position {}: {} of token {}", request.positionId(), totalY, pool.tokenYId());
                return new ClaimFeesResponse(request.positionId(), 0, totalY, null, pool.tokenYId());
            } catch (ArithmeticException overflow) {
                // Conversion overflowed a long (absurd for real fee sizes) — fall
                // back to the standard split credit below so the claim still settles.
                log.warn("Quote-only conversion overflowed for position {}, using split credit", request.positionId());
            }
        }

        // Standard path — credit each accrued token as-is.
        List<UUID> tokenIds = new ArrayList<>(claimedByToken.keySet());
        UUID tokenXId = tokenIds.isEmpty() ? null : tokenIds.get(0);
        UUID tokenYId = tokenIds.size() > 1 ? tokenIds.get(1) : null;
        long claimedX = tokenXId != null ? claimedByToken.getOrDefault(tokenXId, 0L) : 0;
        long claimedY = tokenYId != null ? claimedByToken.getOrDefault(tokenYId, 0L) : 0;

        tokenServiceClient.credit(userId, tokenXId, claimedX);
        if (tokenYId != null && claimedY > 0) {
            tokenServiceClient.credit(userId, tokenYId, claimedY);
        }

        kafkaTemplate.send(FEE_EVENTS_TOPIC, request.positionId().toString(),
                new FeeClaimedEvent(request.positionId(), userId, poolId, claimedX, claimedY, tokenXId, tokenYId, now));
        log.info("Published FeeClaimedEvent for position: {}", request.positionId());

        return new ClaimFeesResponse(request.positionId(), claimedX, claimedY, tokenXId, tokenYId);
    }

    /** Output (raw Y units) for a quote-only claim: claimedY + floor(claimedX × price). Package-private + static for unit testing. */
    static long quoteOnlyTotalY(long claimedX, long claimedY, BigDecimal price) {
        if (claimedX <= 0 || price == null || price.signum() <= 0) return claimedY;
        long feeXInY = BigDecimal.valueOf(claimedX).multiply(price, MC).setScale(0, RoundingMode.FLOOR).longValueExact();
        return claimedY + feeXInY;
    }

    private PoolXY lookupPoolXY(UUID poolId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT token_x_id, token_y_id, base_price FROM liquidity_pools WHERE id = ?",
                    (rs, n) -> new PoolXY(
                            (UUID) rs.getObject("token_x_id"),
                            (UUID) rs.getObject("token_y_id"),
                            rs.getBigDecimal("base_price")),
                    poolId);
        } catch (Exception e) {
            log.warn("Quote-only claim: pool {} lookup failed, falling back to split credit: {}", poolId, e.toString());
            return null;
        }
    }

    private record PoolXY(UUID tokenXId, UUID tokenYId, BigDecimal price) {
    }

    @Transactional(readOnly = true)
    public PageResponse<FeeAccrualDto> getFeeHistory(UUID userId, UUID poolId, int page, int size) {
        log.debug("Getting fee history for user: {}, pool: {}, page: {}, size: {}", userId, poolId, page, size);

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "accruedAt"));

        Page<FeeAccrual> accrualPage;
        if (poolId != null) {
            accrualPage = feeAccrualRepository.findByUserIdAndPoolId(userId, poolId, pageRequest);
        } else {
            accrualPage = feeAccrualRepository.findByUserId(userId, pageRequest);
        }

        List<FeeAccrualDto> dtos = accrualPage.getContent().stream()
                .map(this::toDto)
                .toList();

        return new PageResponse<>(
                dtos,
                accrualPage.getNumber(),
                accrualPage.getSize(),
                accrualPage.getTotalElements(),
                accrualPage.getTotalPages()
        );
    }

    private FeeAccrualDto toDto(FeeAccrual accrual) {
        return new FeeAccrualDto(
                accrual.getId(),
                accrual.getPositionId(),
                accrual.getPoolId(),
                accrual.getTokenId(),
                accrual.getAmount(),
                accrual.isClaimed(),
                accrual.getAccruedAt(),
                accrual.getClaimedAt()
        );
    }

}
