package com.sber.dlmm.fee.service;

import com.sber.dlmm.common.dto.PageResponse;
import com.sber.dlmm.common.exception.IdempotencyConflictException;
import com.sber.dlmm.common.util.BinMath;
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
import org.springframework.dao.EmptyResultDataAccessException;
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
import java.util.LinkedHashSet;
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

        // CLAIMED history: fee_accruals is now write-on-claim only, so every row here is
        // a past payout. Group the claimed amount per pool, per token.
        Map<UUID, Map<UUID, Long>> claimedByPoolToken = new LinkedHashMap<>();
        for (FeeAccrual a : feeAccrualRepository.findByUserId(userId)) {
            claimedByPoolToken
                    .computeIfAbsent(a.getPoolId(), k -> new HashMap<>())
                    .merge(a.getTokenId(), a.getAmount(), Long::sum);
        }

        // UNCLAIMED owed: computed live from pool-engine per-bin fee growth (the single
        // source of truth), per pool, split by the pool's real X/Y token ids. numeric +
        // floor mirrors BinMath.feeFromGrowth exactly and avoids long overflow on the
        // per-bin product. Each row: [poolId, tokenXId, tokenYId, owedX, owedY].
        List<Object[]> owedRows = jdbcTemplate.query(
                "SELECT p.pool_id, lp.token_x_id, lp.token_y_id, " +
                "  COALESCE(SUM(floor(GREATEST(b.fee_growth_x - pb.fee_growth_checkpoint_x,0)::numeric " +
                "      * pb.liquidity_shares / 1000000000)),0)::bigint AS owed_x, " +
                "  COALESCE(SUM(floor(GREATEST(b.fee_growth_y - pb.fee_growth_checkpoint_y,0)::numeric " +
                "      * pb.liquidity_shares / 1000000000)),0)::bigint AS owed_y " +
                "FROM lp_positions p " +
                "JOIN position_bins pb ON pb.position_id = p.id " +
                "JOIN pool_bins b ON b.pool_id = p.pool_id AND b.bin_id = pb.bin_id " +
                "JOIN liquidity_pools lp ON lp.id = p.pool_id " +
                "WHERE p.is_active AND p.user_id = ? " +
                "GROUP BY p.pool_id, lp.token_x_id, lp.token_y_id",
                (rs, n) -> new Object[]{ rs.getObject("pool_id"), rs.getObject("token_x_id"),
                        rs.getObject("token_y_id"), rs.getLong("owed_x"), rs.getLong("owed_y") },
                userId);
        Map<UUID, long[]> owedByPool = new LinkedHashMap<>();          // poolId -> [owedX, owedY]
        Map<UUID, UUID[]> tokensByPool = new LinkedHashMap<>();        // poolId -> [tokenXId, tokenYId]
        for (Object[] r : owedRows) {
            UUID poolId = (UUID) r[0];
            owedByPool.put(poolId, new long[]{ ((Number) r[3]).longValue(), ((Number) r[4]).longValue() });
            tokensByPool.put(poolId, new UUID[]{ (UUID) r[1], (UUID) r[2] });
        }

        List<PoolFeeSummary> poolSummaries = new ArrayList<>();
        long totalUnclaimedX = 0;
        long totalUnclaimedY = 0;
        long totalEarnedX = 0;
        long totalEarnedY = 0;

        Set<UUID> pools = new LinkedHashSet<>();
        pools.addAll(owedByPool.keySet());
        pools.addAll(claimedByPoolToken.keySet());

        for (UUID poolId : pools) {
            UUID[] toks = tokensByPool.get(poolId);
            Map<UUID, Long> claimed = claimedByPoolToken.getOrDefault(poolId, Map.of());
            UUID tokenXId;
            UUID tokenYId;
            if (toks != null) {
                tokenXId = toks[0];
                tokenYId = toks[1];
            } else {
                // Pool with claimed history but no active position — best-effort labels.
                List<UUID> ids = new ArrayList<>(claimed.keySet());
                tokenXId = ids.isEmpty() ? null : ids.get(0);
                tokenYId = ids.size() > 1 ? ids.get(1) : null;
            }

            long[] owed = owedByPool.getOrDefault(poolId, new long[]{0, 0});
            long unclaimedFeeX = owed[0];
            long unclaimedFeeY = owed[1];
            long claimedX = tokenXId != null ? claimed.getOrDefault(tokenXId, 0L) : 0;
            long claimedY = tokenYId != null ? claimed.getOrDefault(tokenYId, 0L) : 0;
            // earned = already-claimed (history) + still-owed (live per-bin).
            long earnedFeeX = claimedX + unclaimedFeeX;
            long earnedFeeY = claimedY + unclaimedFeeY;

            totalUnclaimedX += unclaimedFeeX;
            totalUnclaimedY += unclaimedFeeY;
            totalEarnedX += earnedFeeX;
            totalEarnedY += earnedFeeY;

            poolSummaries.add(new PoolFeeSummary(
                    poolId,
                    poolId.toString(),
                    unclaimedFeeX,
                    unclaimedFeeY,
                    earnedFeeX,
                    earnedFeeY,
                    BigDecimal.ZERO
            ));
        }

        long totalUnclaimed = totalUnclaimedX + totalUnclaimedY;
        long totalClaimed = (totalEarnedX + totalEarnedY) - totalUnclaimed;

        return new FeesSummaryResponse(userId, poolSummaries, totalUnclaimedX, totalUnclaimedY,
                totalEarnedX, totalEarnedY, totalClaimed, totalUnclaimed);
    }

    /**
     * A position's currently-OWED (unclaimed) fees, computed live from pool-engine's
     * per-bin fee growth — the single source of truth used by the claim and the summary.
     * Drives the auto-claim scheduler's work discovery (fee_accruals no longer holds
     * unclaimed rows).
     */
    public record PositionOwed(UUID positionId, UUID poolId, long owedX, long owedY) {}

    /**
     * Per-position unclaimed owed for a user's ACTIVE positions, computed from per-bin
     * fee growth (numeric + floor mirrors {@link BinMath#feeFromGrowth} and avoids long
     * overflow on the per-bin product) plus the stored {@code unclaimed_fee} column.
     * Positions with zero owed are included; callers filter as needed.
     */
    @Transactional(readOnly = true)
    public List<PositionOwed> getUnclaimedOwedByPosition(UUID userId) {
        return jdbcTemplate.query(
                "SELECT p.id, p.pool_id, " +
                "  (COALESCE(SUM(floor(GREATEST(b.fee_growth_x - pb.fee_growth_checkpoint_x,0)::numeric " +
                "      * pb.liquidity_shares / 1000000000)),0) + p.unclaimed_fee_x)::bigint AS owed_x, " +
                "  (COALESCE(SUM(floor(GREATEST(b.fee_growth_y - pb.fee_growth_checkpoint_y,0)::numeric " +
                "      * pb.liquidity_shares / 1000000000)),0) + p.unclaimed_fee_y)::bigint AS owed_y " +
                "FROM lp_positions p " +
                "JOIN position_bins pb ON pb.position_id = p.id " +
                "JOIN pool_bins b ON b.pool_id = p.pool_id AND b.bin_id = pb.bin_id " +
                "WHERE p.is_active AND p.user_id = ? " +
                "GROUP BY p.id, p.pool_id, p.unclaimed_fee_x, p.unclaimed_fee_y",
                (rs, n) -> new PositionOwed((UUID) rs.getObject("id"), (UUID) rs.getObject("pool_id"),
                        rs.getLong("owed_x"), rs.getLong("owed_y")),
                userId);
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
     * <p><b>Flow (Meteora per-bin single source of truth).</b> (1) If an idempotency
     * key is supplied, reserve it; reject with {@link IdempotencyConflictException} if
     * already seen, and register a rollback-only release so a failed attempt can be
     * retried. (2) Compute owed live from pool-engine's per-bin fee growth:
     * {@code stored unclaimed_fee + Σ feeFromGrowth(pool_bins.fee_growth -
     * position_bins.fee_growth_checkpoint, shares)} — exactly what the Positions page
     * displays; return a zero response if owed is 0. (3) Advance each bin's checkpoint
     * to the measured growth and zero the stored column (settle). (4) Credit balances,
     * write a {@code claimed=true} history row to {@code fee_accruals}, and emit the
     * event. Steps 3–4 share one transaction, so a credit failure rolls the settle back
     * and the fees stay claimable (never settled-but-unpaid). A re-claim then measures
     * owed=0 — structurally no double credit.
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

        // ── Per-bin owed: pool-engine fee growth is the SINGLE SOURCE OF TRUTH ──
        // owed = stored unclaimed_fee + Σ feeFromGrowth(pool_bins.fee_growth -
        // position_bins.fee_growth_checkpoint, shares) — EXACTLY what getUserPositions
        // displays, so a claim pays what the Positions page shows. fee_accruals is no
        // longer the source; it is written below purely as claim history.
        LocalDateTime now = LocalDateTime.now();

        // Position row: owner (you may only claim your own), pool, and the stored
        // unclaimed column (0 under the per-bin model, folded in for completeness).
        Map<String, Object> posRow;
        try {
            posRow = jdbcTemplate.queryForMap(
                    "SELECT pool_id, user_id, unclaimed_fee_x, unclaimed_fee_y FROM lp_positions WHERE id = ?",
                    request.positionId());
        } catch (EmptyResultDataAccessException notFound) {
            return new ClaimFeesResponse(request.positionId(), 0, 0, null, null);
        }
        if (!userId.equals(posRow.get("user_id"))) {
            return new ClaimFeesResponse(request.positionId(), 0, 0, null, null);
        }
        UUID poolId = (UUID) posRow.get("pool_id");
        long owedX = ((Number) posRow.get("unclaimed_fee_x")).longValue();
        long owedY = ((Number) posRow.get("unclaimed_fee_y")).longValue();

        // Per-bin growth vs this position's per-bin checkpoint (mirrors getUserPositions).
        // Each row: [binId, growthX, growthY, checkpointX, checkpointY, shares].
        List<long[]> bins = jdbcTemplate.query(
                "SELECT pb.bin_id, b.fee_growth_x, b.fee_growth_y, " +
                "       pb.fee_growth_checkpoint_x, pb.fee_growth_checkpoint_y, pb.liquidity_shares " +
                "FROM position_bins pb " +
                "JOIN lp_positions p ON p.id = pb.position_id " +
                "JOIN pool_bins b ON b.pool_id = p.pool_id AND b.bin_id = pb.bin_id " +
                "WHERE pb.position_id = ?",
                (rs, n) -> new long[]{ rs.getLong(1), rs.getLong(2), rs.getLong(3),
                                       rs.getLong(4), rs.getLong(5), rs.getLong(6) },
                request.positionId());
        for (long[] r : bins) {
            owedX += BinMath.feeFromGrowth(r[1] - r[3], r[5]);
            owedY += BinMath.feeFromGrowth(r[2] - r[4], r[5]);
        }

        if (owedX <= 0 && owedY <= 0) {
            return new ClaimFeesResponse(request.positionId(), 0, 0, null, null);
        }

        // Resolve the pool's token ids + price BEFORE settling, so we never advance
        // checkpoints (and thereby lose the fees) for a position we can't credit.
        PoolXY pool = lookupPoolXY(poolId);
        if (pool == null) {
            log.warn("Pool {} not readable for claim on position {} — skipping settle", poolId, request.positionId());
            return new ClaimFeesResponse(request.positionId(), 0, 0, null, null);
        }
        UUID tokenXId = pool.tokenXId();
        UUID tokenYId = pool.tokenYId();

        // Settle: advance each bin's checkpoint to the growth we just MEASURED (explicit
        // values, NOT a fresh subquery — a swap landing mid-claim keeps its increment for
        // the next claim instead of being lost), and zero the stored column. Same txn as
        // the credit below, so a credit failure rolls this back and fees stay claimable;
        // a re-claim then measures owed=0 (structurally no double credit).
        List<Object[]> advance = new ArrayList<>(bins.size());
        for (long[] r : bins) {
            advance.add(new Object[]{ r[1], r[2], request.positionId(), (int) r[0] });
        }
        if (!advance.isEmpty()) {
            jdbcTemplate.batchUpdate(
                    "UPDATE position_bins SET fee_growth_checkpoint_x = ?, fee_growth_checkpoint_y = ? " +
                    "WHERE position_id = ? AND bin_id = ?",
                    advance);
        }
        jdbcTemplate.update(
                "UPDATE lp_positions SET unclaimed_fee_x = 0, unclaimed_fee_y = 0 WHERE id = ?",
                request.positionId());

        // Quote-only — convert the X-fee to the quote token (Y) and credit one token.
        if (quoteOnly) {
            try {
                long totalY = quoteOnlyTotalY(owedX, owedY, pool.price());
                if (totalY > 0) {
                    tokenServiceClient.credit(userId, tokenYId, totalY);
                }
                writeClaimHistory(request.positionId(), poolId, userId, tokenYId, totalY, now);
                kafkaTemplate.send(FEE_EVENTS_TOPIC, request.positionId().toString(),
                        new FeeClaimedEvent(request.positionId(), userId, poolId, 0, totalY, null, tokenYId, now));
                log.info("Claimed fees (quote-only, per-bin) position {}: {} of token {}",
                        request.positionId(), totalY, tokenYId);
                return new ClaimFeesResponse(request.positionId(), 0, totalY, null, tokenYId);
            } catch (ArithmeticException overflow) {
                // Conversion overflowed a long (absurd for real fee sizes) — fall back
                // to the standard split credit below so the claim still settles.
                log.warn("Quote-only conversion overflowed for position {}, using split credit", request.positionId());
            }
        }

        // Standard split credit — pay each owed leg to its real token, label X/Y by the
        // pool's real token ids, and record claim-history rows for fee history/summary.
        if (owedX > 0) {
            tokenServiceClient.credit(userId, tokenXId, owedX);
        }
        if (owedY > 0) {
            tokenServiceClient.credit(userId, tokenYId, owedY);
        }
        writeClaimHistory(request.positionId(), poolId, userId, tokenXId, owedX, now);
        writeClaimHistory(request.positionId(), poolId, userId, tokenYId, owedY, now);

        kafkaTemplate.send(FEE_EVENTS_TOPIC, request.positionId().toString(),
                new FeeClaimedEvent(request.positionId(), userId, poolId, owedX, owedY, tokenXId, tokenYId, now));
        log.info("Claimed fees (per-bin) position {}: X={} Y={}", request.positionId(), owedX, owedY);

        return new ClaimFeesResponse(request.positionId(), owedX, owedY, tokenXId, tokenYId);
    }

    /**
     * Records a CLAIMED ({@code claimed=true}) fee-accrual history row so the fee
     * history endpoint, the {@code totalClaimed} summary and the CLAIM_FEE transaction
     * keep working under the per-bin model — where {@code fee_accruals} is no longer the
     * claim SOURCE, only an audit trail of what was paid out. No-op for a missing token
     * or non-positive amount. Shares the caller's transaction.
     */
    private void writeClaimHistory(UUID positionId, UUID poolId, UUID userId, UUID tokenId, long amount, LocalDateTime when) {
        if (tokenId == null || amount <= 0) {
            return;
        }
        feeAccrualRepository.save(FeeAccrual.builder()
                .positionId(positionId)
                .poolId(poolId)
                .userId(userId)
                .tokenId(tokenId)
                .amount(amount)
                .claimed(true)
                .accruedAt(when)
                .claimedAt(when)
                .build());
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
