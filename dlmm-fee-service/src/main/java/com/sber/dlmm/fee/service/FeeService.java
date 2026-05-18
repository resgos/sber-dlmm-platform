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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

        return new FeesSummaryResponse(userId, poolSummaries, totalUnclaimedX, totalUnclaimedY);
    }

    @Transactional
    public ClaimFeesResponse claimFees(ClaimFeesRequest request, UUID userId) {
        log.info("Claiming fees for position: {} by user: {}", request.positionId(), userId);

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

        List<UUID> tokenIds = new ArrayList<>(claimedByToken.keySet());
        UUID tokenXId = tokenIds.isEmpty() ? null : tokenIds.get(0);
        UUID tokenYId = tokenIds.size() > 1 ? tokenIds.get(1) : null;
        long claimedX = tokenXId != null ? claimedByToken.getOrDefault(tokenXId, 0L) : 0;
        long claimedY = tokenYId != null ? claimedByToken.getOrDefault(tokenYId, 0L) : 0;

        UUID poolId = unclaimedAccruals.get(0).getPoolId();

        tokenServiceClient.credit(userId, tokenXId, claimedX);
        if (tokenYId != null && claimedY > 0) {
            tokenServiceClient.credit(userId, tokenYId, claimedY);
        }

        FeeClaimedEvent event = new FeeClaimedEvent(
                request.positionId(),
                userId,
                poolId,
                claimedX,
                claimedY,
                tokenXId,
                tokenYId,
                now
        );
        kafkaTemplate.send(FEE_EVENTS_TOPIC, request.positionId().toString(), event);
        log.info("Published FeeClaimedEvent for position: {}", request.positionId());

        return new ClaimFeesResponse(request.positionId(), claimedX, claimedY, tokenXId, tokenYId);
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
