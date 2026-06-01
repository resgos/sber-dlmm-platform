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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

/**
 * Core LP-fee service: reads a user's accrued trading fees, claims them (crediting
 * real token balances), and serves paginated history. This class moves money, so the
 * methods below are written defensively around three money-safety invariants:
 *
 * <ul>
 *   <li><b>Idempotency.</b> {@link #claimFees(ClaimFeesRequest, UUID, boolean)} dedups
 *       on the request's idempotency key via {@link #processedIdempotencyKeys}; a key
 *       that was already accepted is rejected with {@link IdempotencyConflictException}
 *       so a retried claim can never credit twice. The key is released again if the
 *       enclosing transaction rolls back, so a legitimate retry after a transient
 *       failure isn't permanently locked out.</li>
 *   <li><b>In-transaction credit.</b> The claim runs under {@code @Transactional}: the
 *       accrual rows are flipped to {@code claimed=true} and the balance credit
 *       ({@link TokenServiceClient#credit}) both happen inside the same transaction.
 *       If the credit call fails the whole claim rolls back, so we never mark fees
 *       claimed without actually paying them out.</li>
 *   <li><b>Quote-only consolidation.</b> In quote-only mode the X-side fee is converted
 *       to the pool's quote token (Y) at the pool's current price and the LP receives a
 *       single token instead of two. If the pool can't be read, or the conversion
 *       overflows a {@code long}, the code falls back to the standard split credit so
 *       fees are never lost.</li>
 * </ul>
 *
 * <p>All token amounts here are raw integer units (1 unit = 10⁻⁴ token, see the
 * platform-wide amount-scale convention); this service never applies a token's
 * {@code decimals} column. {@link MathContext#DECIMAL128} is used for the X→Y price
 * conversion and the result is floored, so the platform never over-credits a fraction.
 *
 * <p>The idempotency cache is in-memory ({@link ConcurrentHashMap}), so it dedups within
 * a single JVM. Cross-replica safety for the automated path is provided upstream by the
 * scheduler's ShedLock plus the deterministic per-minute key it generates.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FeeService {

    /** Kafka topic onto which a {@link FeeClaimedEvent} is published after every successful claim. */
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

    /** 34-digit precision for the X→Y price conversion; the conversion result is floored to a whole unit. */
    private static final MathContext MC = MathContext.DECIMAL128;

    /**
     * In-memory dedup set of idempotency keys already accepted by {@link #claimFees}.
     * Thread-safe ({@link ConcurrentHashMap#newKeySet()}) so concurrent claims in the
     * same JVM can't both pass the {@code add()} check. A key is removed again on
     * transaction rollback (see {@link #claimFees}) so a genuine retry isn't blocked.
     * Process-local only — it does not survive a restart or dedup across replicas.
     */
    private final Set<String> processedIdempotencyKeys = ConcurrentHashMap.newKeySet();

    /**
     * Aggregates a user's fee accruals across all pools into a dashboard summary:
     * per-pool earned/unclaimed totals for each side plus platform-wide rollups.
     *
     * <p>Read-only (no money moves). Accruals are grouped by pool, then by token within
     * the pool; the two distinct token ids encountered are labelled X and Y purely by
     * iteration order here (a display nicety — the per-token amounts are what matter).
     * {@code totalClaimed} is derived as {@code (earnedX+earnedY) - totalUnclaimed},
     * i.e. everything ever earned that is no longer outstanding.
     *
     * @param userId the user whose accruals to summarise (taken from the JWT upstream)
     * @return a {@link FeesSummaryResponse} with per-pool summaries and platform-wide totals
     */
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

    /**
     * Claims accrued fees with the standard split credit (X and Y paid separately).
     * Convenience overload delegating to {@link #claimFees(ClaimFeesRequest, UUID, boolean)}
     * with {@code quoteOnly=false}; this is the path the auto-claim scheduler uses.
     *
     * <p>Annotated {@code @Transactional} so that, when called as a Spring bean method,
     * the accrual flips and balance credits commit (or roll back) atomically.
     *
     * @param request carries the {@code positionId} to claim and an optional idempotency key
     * @param userId  the owner of the accruals; only this user's unclaimed fees are settled
     * @return the claimed amounts and the X/Y token ids they were credited to
     * @throws IdempotencyConflictException if the request's idempotency key was already processed
     */
    @Transactional
    public ClaimFeesResponse claimFees(ClaimFeesRequest request, UUID userId) {
        return claimFees(request, userId, false);
    }

    /**
     * Claims a position's unclaimed fees: marks the matching accrual rows claimed and
     * credits the amounts to the user's token balances, then publishes a
     * {@link FeeClaimedEvent}. This is the money path — read the body alongside the
     * class-level money-safety invariants.
     *
     * <p><b>Flow.</b> (1) If an idempotency key is supplied, reserve it; reject with
     * {@link IdempotencyConflictException} if already seen, and register a
     * rollback-only release so a failed attempt can be retried. (2) Load this user's
     * unclaimed accruals for the position; return a zero response if there are none.
     * (3) Flip them to {@code claimed} and persist. (4) Credit balances and emit the
     * event. Steps 3–4 share one transaction, so a credit failure rolls the claim back
     * and the fees stay unclaimed (never marked-paid-but-unpaid).
     *
     * <p>Sprint 16 (Meteora parity) — {@code quoteOnly} consolidates the claim into
     * the pool's quote token (Y): the X-fee is converted at the pool's current
     * price and credited as Y, so the LP receives a single token instead of two.
     * If the pool can't be read, it falls back to the standard split credit so
     * fees are never lost.
     *
     * <p><b>Why look the pool up even on the split path:</b> the X/Y side <i>labels</i>
     * on the event and response come from the pool's real token ids, not from
     * {@code HashMap} iteration order (which is hash-order and could otherwise swap the
     * reported sides — balances were always correct, only the labels could invert).
     *
     * @param request   the position to claim and an optional idempotency key for safe retries
     * @param userId    the owner of the accruals; foreign users' fees are filtered out
     * @param quoteOnly when {@code true}, convert the X-fee to Y and credit a single token;
     *                  silently degrades to the split credit if the pool is unreadable or the
     *                  conversion overflows a {@code long}
     * @return the credited amounts ({@code claimedX} is 0 in a successful quote-only claim)
     *         plus the X/Y token ids; an all-zero response means nothing was unclaimed
     * @throws IdempotencyConflictException if the supplied idempotency key was already processed
     */
    @Transactional
    public ClaimFeesResponse claimFees(ClaimFeesRequest request, UUID userId, boolean quoteOnly) {
        log.info("Claiming fees for position: {} by user: {} (quoteOnly={})", request.positionId(), userId, quoteOnly);

        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            String idemKey = request.idempotencyKey();
            if (!processedIdempotencyKeys.add(idemKey)) {
                throw new IdempotencyConflictException(
                        "Request with idempotency key '" + idemKey + "' has already been processed");
            }
            // Release the key if this transaction rolls back, so a legitimate retry
            // (e.g. after a transient credit failure) isn't permanently blocked.
            // Mirrors the pool-engine #23 fix.
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) {
                            processedIdempotencyKeys.remove(idemKey);
                        }
                    }
                });
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

        // Standard path — credit every accrued token (nothing dropped), and label
        // X/Y by the pool's REAL token ids. HashMap.keySet() order is hash-order, so
        // the legacy get(0)/get(1) could swap the X/Y labels on the event + response
        // (balances were always correct; only the reported sides could be inverted).
        for (Map.Entry<UUID, Long> e : claimedByToken.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) {
                tokenServiceClient.credit(userId, e.getKey(), e.getValue());
            }
        }

        PoolXY labelPool = (pool != null) ? pool : lookupPoolXY(poolId);
        UUID tokenXId;
        UUID tokenYId;
        if (labelPool != null) {
            tokenXId = labelPool.tokenXId();
            tokenYId = labelPool.tokenYId();
        } else {
            List<UUID> tokenIds = new ArrayList<>(claimedByToken.keySet());
            tokenXId = tokenIds.isEmpty() ? null : tokenIds.get(0);
            tokenYId = tokenIds.size() > 1 ? tokenIds.get(1) : null;
        }
        long claimedX = tokenXId != null ? claimedByToken.getOrDefault(tokenXId, 0L) : 0;
        long claimedY = tokenYId != null ? claimedByToken.getOrDefault(tokenYId, 0L) : 0;

        kafkaTemplate.send(FEE_EVENTS_TOPIC, request.positionId().toString(),
                new FeeClaimedEvent(request.positionId(), userId, poolId, claimedX, claimedY, tokenXId, tokenYId, now));
        log.info("Published FeeClaimedEvent for position: {}", request.positionId());

        return new ClaimFeesResponse(request.positionId(), claimedX, claimedY, tokenXId, tokenYId);
    }

    /**
     * Computes the single-token output (raw Y units) for a quote-only claim:
     * {@code claimedY + floor(claimedX × price)}. The X-fee is valued in Y at the pool
     * price and <i>floored</i> so the platform never over-credits a fraction of a unit.
     * Package-private + {@code static} so the conversion can be unit-tested without a pool.
     *
     * <p>Defensive guards: a non-positive {@code claimedX} or a missing/non-positive
     * {@code price} means "nothing to convert", so the caller's {@code claimedY} is
     * returned unchanged.
     *
     * @param claimedX raw X-side amount being converted into Y (≤ 0 ⇒ no conversion)
     * @param claimedY raw Y-side amount already in the quote token
     * @param price    pool price as Y per 1 X ({@code null} or ≤ 0 ⇒ no conversion)
     * @return total raw Y units to credit
     * @throws ArithmeticException if the floored conversion exceeds {@code Long.MAX_VALUE}
     *         (absurd for real fee sizes); the caller catches this and falls back to the
     *         split credit so the claim still settles
     */
    static long quoteOnlyTotalY(long claimedX, long claimedY, BigDecimal price) {
        if (claimedX <= 0 || price == null || price.signum() <= 0) return claimedY;
        long feeXInY = BigDecimal.valueOf(claimedX).multiply(price, MC).setScale(0, RoundingMode.FLOOR).longValueExact();
        return claimedY + feeXInY;
    }

    /**
     * Reads a pool's X/Y token ids and base price straight from the shared
     * {@code liquidity_pools} table (fee-service owns no pool entity). Used both to
     * convert the X-fee in quote-only mode and to label the X/Y sides correctly on the
     * standard path.
     *
     * <p>Fail-soft: any lookup error is logged and returns {@code null} rather than
     * propagating, so a transient DB hiccup degrades to the split credit instead of
     * failing the whole claim and losing the fees.
     *
     * @param poolId the pool whose token ids and price to fetch
     * @return a {@link PoolXY} snapshot, or {@code null} if the pool can't be read
     */
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

    /**
     * Immutable snapshot of the pool fields the claim path needs: the two token ids and
     * the base price (Y per 1 X) used to value an X-fee in the quote token.
     *
     * @param tokenXId the pool's base (X) token id
     * @param tokenYId the pool's quote (Y) token id
     * @param price    pool base price as Y per 1 X
     */
    private record PoolXY(UUID tokenXId, UUID tokenYId, BigDecimal price) {
    }

    /**
     * Returns a page of the user's fee accrual records (claimed and unclaimed), newest
     * first by accrual time, optionally narrowed to a single pool. Read-only.
     *
     * @param userId the user whose history to read
     * @param poolId optional pool filter; when {@code null}, all of the user's pools are included
     * @param page   zero-based page index
     * @param size   page size
     * @return a {@link PageResponse} of {@link FeeAccrualDto} with paging metadata
     */
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

    /**
     * Maps a {@link FeeAccrual} JPA entity to its API {@link FeeAccrualDto}, copying id,
     * position/pool/token ids, raw amount, claimed flag, and the accrued/claimed timestamps.
     *
     * @param accrual the persisted accrual entity
     * @return the transport DTO for the API response
     */
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
