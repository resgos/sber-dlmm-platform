package com.sber.dlmm.admin.service;

import com.sber.dlmm.admin.client.BffDownstreamClient;
import com.sber.dlmm.admin.client.PoolEngineClient;
import com.sber.dlmm.admin.dto.DashboardResponse;
import com.sber.dlmm.admin.dto.PoolAnalyticsResponse;
import com.sber.dlmm.admin.dto.PoolAnalyticsResponse.BinDistributionEntry;
import com.sber.dlmm.admin.dto.PoolAnalyticsResponse.FeeHistoryEntry;
import com.sber.dlmm.admin.dto.PoolAnalyticsResponse.TopLpEntry;
import com.sber.dlmm.admin.dto.PoolAnalyticsResponse.TvlHistoryEntry;
import com.sber.dlmm.admin.dto.PoolAnalyticsResponse.VolumeHistoryEntry;
import com.sber.dlmm.admin.dto.SuspiciousTransactionResponse;
import com.sber.dlmm.admin.dto.TokenAnalyticsResponse;
import com.sber.dlmm.admin.dto.TokenAnalyticsResponse.PriceHistoryEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Core aggregation service of the admin BFF — turns the admin dashboard and
 * the per-pool / per-token analytics screens into single calls by fanning out
 * to the downstream domain services and reshaping their raw JSON into the
 * platform's DTOs.
 *
 * <p>It owns no data; every figure here is computed on the fly from downstream
 * responses fetched through {@link PoolEngineClient} and
 * {@link BffDownstreamClient}. Those clients wrap each call in a Resilience4j
 * circuit-breaker + retry whose fallback returns an empty collection / map, so
 * this class can aggregate without null-checking the transport: a degraded
 * downstream simply contributes zero rows, and the dashboard still renders
 * (numbers just read low) rather than failing.
 *
 * <p>History note: an earlier version held six raw {@code WebClient}s and used
 * {@code Mono.zip(...).block(...)}, which (a) stalled the whole dashboard on any
 * one slow downstream and (b) mis-parsed {@code Page<...>} bodies. Calls are now
 * sequential and individually circuit-broken (see the inline comments on each
 * method for the specific bugs that drove the rewrite).
 *
 * <p>All the JSON-coercion helpers at the bottom ({@code toLong}, {@code toBigDecimal},
 * etc.) are deliberately lenient — downstream responses are {@code Map<String,Object>}
 * with no compile-time schema, so each helper degrades a missing / wrong-typed
 * field to a neutral default rather than throwing mid-aggregation.
 */
@Slf4j
@Service
public class AdminService {

    // Smaller page than the previous 1000 — seed catalog is 22 pools / 4
    // users / 15 tx; 200 leaves headroom for growth without making the
    // unbounded fetch slower than it has to be.
    /** Page size for the single bounded "fetch everything" calls that back the dashboard aggregation (see inline note for why 200, not 1000). */
    private static final int AGGREGATION_PAGE_SIZE = 200;
    /** Price-impact (%) above which a transaction is flagged suspicious in {@link #getSuspiciousTransactions()}. */
    private static final BigDecimal PRICE_IMPACT_THRESHOLD = new BigDecimal("3.0");
    /** Per-user transaction count in the scanned batch above which that user's transactions are flagged as high-frequency. */
    private static final long HIGH_FREQUENCY_THRESHOLD = 50;

    // Sprint 9-DS-r4 (P1-13) — every downstream now goes through a
    // Resilience4j-wrapped client. The previous shape (six raw WebClient
    // fields with per-call inline `.onErrorReturn(...)`) caught HTTP
    // errors but left threads pinned on slow downstreams until each
    // 8-second timeout expired — a stuck token-service could saturate
    // the bff. With Resilience4j a 50%-failure CB trips fast and frees
    // the threads.
    private final PoolEngineClient poolEngine;
    private final BffDownstreamClient downstream;

    /**
     * @param poolEngine circuit-broken client for pool-engine (pools list,
     *                   pool detail, active-position count)
     * @param downstream circuit-broken client for the other five downstreams
     *                   (user / token / fee / transaction / price-oracle)
     */
    public AdminService(PoolEngineClient poolEngine, BffDownstreamClient downstream) {
        this.poolEngine = poolEngine;
        this.downstream = downstream;
    }

    /**
     * Builds the admin home-dashboard summary in one shot: user counts, pool
     * counts, aggregate TVL / 24h volume / fees, active positions, and today's
     * transaction count.
     *
     * <p>Issues three independent, individually circuit-broken page fetches
     * (users, pools, transactions) plus a position-count call. Because each
     * client fallback yields an empty collection, a degraded downstream just
     * contributes zero to its slice — the method never throws on downstream
     * failure and always returns a fully-populated DTO.
     *
     * <p>Caveats baked into the numbers: TVL/volume/fees are summed in mixed
     * token smallest-units (no shared decimals model yet — order-of-magnitude
     * useful, not price-normalised); "transactions today" filters the fetched
     * page by {@code createdAt} date prefix, so it reflects only today's rows
     * within the (bounded) page.
     *
     * @return a fully-populated {@link DashboardResponse} (zeros for any slice
     *         whose downstream was unavailable)
     */
    public DashboardResponse getDashboard() {
        log.debug("Fetching dashboard data from all microservices");

        // Independent blocking calls with per-call timeout. The previous
        // Mono.zip(...) implementation had two bugs:
        //   1. it parsed Page<...> responses as List<Map>, which Jackson
        //      couldn't deserialise — onErrorReturn was never wired against
        //      that failure mode so the Mono stalled silently;
        //   2. any single slow downstream stalled the entire dashboard
        //      because zip awaits all three signals.
        // Now each call is bounded by REQUEST_TIMEOUT and degrades to an
        // empty list independently.
        // Sprint 9-DS-r4 (P1-13) — every downstream now CB-wrapped.
        // Fallbacks all return Collections.emptyList() so the aggregation
        // below sees the same shape on degradation.
        List<Map<String, Object>> users = downstream.fetchUsersPage(AGGREGATION_PAGE_SIZE);
        List<Map<String, Object>> pools = poolEngine.fetchPoolsPage(AGGREGATION_PAGE_SIZE);
        List<Map<String, Object>> transactions = downstream.fetchTransactionsPage(AGGREGATION_PAGE_SIZE);

        long totalUsers = users.size();
        // user-service returns kycStatus as a string ("VERIFIED" | "PENDING" | …)
        // toString(...) guards against null / unexpected types.
        long verifiedUsers = users.stream()
                .filter(u -> "VERIFIED".equals(toString(u.get("kycStatus"))))
                .count();

        int totalPools = pools.size();
        int activePools = (int) pools.stream()
                .filter(p -> "ACTIVE".equals(p.get("status")))
                .count();

        // Both sides of every pool contribute to TVL. The result is in mixed
        // token smallest units (no shared decimals model yet) but already
        // ordered-of-magnitude useful for the admin view; price-aware
        // aggregation is a follow-up once the precision/decimals story is
        // cleaned up.
        BigDecimal totalTvlRub = pools.stream()
                .map(p -> toBigDecimal(p.get("totalTvlX")).add(toBigDecimal(p.get("totalTvlY"))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal volume24hRub = pools.stream()
                .map(p -> toBigDecimal(p.get("volume24h")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalFeesCollectedRub = pools.stream()
                .map(p -> toBigDecimal(p.get("totalFeesCollectedX"))
                        .add(toBigDecimal(p.get("totalFeesCollectedY"))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // activePositions: ask pool-engine via the circuit-broken client.
        // Sprint 8 C-10 — used to be a private fetchActivePositionsCount()
        // with inline onErrorResume; replaced by PoolEngineClient which
        // adds @CircuitBreaker + @Retry on top of the same fallback-to-0
        // semantics.
        long activePositions = poolEngine.fetchActivePositionsCount();

        // transactionsToday was counting the entire page size — the page can
        // hold up to AGGREGATION_PAGE_SIZE rows of history, none of which are
        // necessarily from today. Filter by createdAt date prefix so the
        // number actually reflects today's activity.
        String todayPrefix = LocalDate.now().toString();
        long transactionsToday = transactions.stream()
                .filter(t -> toString(t.get("createdAt")).startsWith(todayPrefix))
                .count();

        DashboardResponse response = new DashboardResponse(
                totalUsers, verifiedUsers, totalPools, activePools,
                totalTvlRub, volume24hRub, totalFeesCollectedRub,
                activePositions, transactionsToday
        );

        log.debug("Dashboard assembled: users={}, pools={}, tx={}",
                totalUsers, totalPools, transactionsToday);

        return response;
    }

    // Sprint 8 C-10 — fetchActivePositionsCount() moved to
    // {@link com.sber.dlmm.admin.client.PoolEngineClient} so it can be
    // @CircuitBreaker'd. The signature, return-on-failure semantics, and
    // log shape are preserved by the new client (verified by manual diff).

    /**
     * Assembles the per-pool analytics view: TVL / volume / bin-distribution /
     * top-LP series come from the pool-detail document, the fee series from the
     * fee-service.
     *
     * <p>Two sequential, individually circuit-broken calls (pool detail +
     * fee history). On a degraded downstream the corresponding source is an
     * empty map/list, so the affected series come back empty while the rest
     * still render. Total latency is the sum of the two (vs the old
     * {@code max(p,f)} of the zipped version) — an accepted trade-off, since
     * the upside is failure isolation and both calls are ~ms when healthy.
     *
     * @param poolId id of the pool to analyse
     * @return a {@link PoolAnalyticsResponse} with whatever series were
     *         retrievable (empty lists for any unavailable source)
     */
    public PoolAnalyticsResponse getPoolAnalytics(UUID poolId) {
        log.debug("Fetching pool analytics for poolId={}", poolId);

        // Sprint 9-DS-r4 (P1-13) — sequential CB-wrapped calls in place
        // of the previous Mono.zip(...).block(REQUEST_TIMEOUT). Each side
        // has its own breaker, so a stuck fee-service doesn't drag down
        // the pool-detail render and vice-versa. Total latency is the
        // sum (was max(p,f)), but on a healthy system both are ~ms and
        // the trade-off pays off on the failure path.
        Map<String, Object> poolDetail = poolEngine.fetchPoolDetail(poolId);
        List<Map<String, Object>> feeHistory = downstream.fetchPoolFeeHistory(poolId, 30);

        List<TvlHistoryEntry> tvlHistory = extractList(poolDetail, "tvlHistory").stream()
                .map(entry -> new TvlHistoryEntry(
                        parseDateTime(entry.get("timestamp")),
                        toLong(entry.get("tvlX")),
                        toLong(entry.get("tvlY"))
                ))
                .toList();

        List<VolumeHistoryEntry> volumeHistory = extractList(poolDetail, "volumeHistory").stream()
                .map(entry -> new VolumeHistoryEntry(
                        parseDateTime(entry.get("timestamp")),
                        toLong(entry.get("volume"))
                ))
                .toList();

        List<FeeHistoryEntry> feeHistoryEntries = feeHistory.stream()
                .map(entry -> new FeeHistoryEntry(
                        parseDateTime(entry.get("timestamp")),
                        toLong(entry.get("feeX")),
                        toLong(entry.get("feeY"))
                ))
                .toList();

        List<BinDistributionEntry> binDistribution = extractList(poolDetail, "binDistribution").stream()
                .map(entry -> new BinDistributionEntry(
                        toInt(entry.get("binId")),
                        toBigDecimal(entry.get("price")),
                        toLong(entry.get("liquidity")),
                        toLong(entry.get("reserveX")),
                        toLong(entry.get("reserveY"))
                ))
                .toList();

        List<TopLpEntry> topLPs = extractList(poolDetail, "topLPs").stream()
                .map(entry -> new TopLpEntry(
                        toUUID(entry.get("userId")),
                        toString(entry.get("name")),
                        toLong(entry.get("totalLiquidity")),
                        toLong(entry.get("earnedFees"))
                ))
                .toList();

        log.debug("Pool analytics assembled for poolId={}: tvlHistory={} entries, bins={} entries",
                poolId, tvlHistory.size(), binDistribution.size());

        return new PoolAnalyticsResponse(tvlHistory, volumeHistory, feeHistoryEntries, binDistribution, topLPs);
    }

    /**
     * Assembles the per-token analytics view: supply / holders / 24h transfer
     * volume from the token-detail document, plus a price-history series from the
     * price-oracle.
     *
     * <p>Same shape as {@link #getPoolAnalytics}: two sequential circuit-broken
     * calls, each degrading to empty independently.
     *
     * @param tokenId id of the token to analyse
     * @return a {@link TokenAnalyticsResponse}; scalar fields read 0 and the
     *         price history is empty if the respective downstream was unavailable
     */
    public TokenAnalyticsResponse getTokenAnalytics(UUID tokenId) {
        log.debug("Fetching token analytics for tokenId={}", tokenId);

        // Sprint 9-DS-r4 (P1-13) — sequential CB-wrapped calls, same
        // trade-off as getPoolAnalytics above.
        Map<String, Object> tokenDetail = downstream.fetchTokenDetail(tokenId);
        List<Map<String, Object>> priceHistoryRaw = downstream.fetchPriceHistory(tokenId, 30);

        long totalSupply = toLong(tokenDetail.get("totalSupply"));
        long circulatingSupply = toLong(tokenDetail.get("circulatingSupply"));
        int holders = toInt(tokenDetail.get("holders"));
        long transferVolume24h = toLong(tokenDetail.get("transferVolume24h"));

        List<PriceHistoryEntry> priceHistory = priceHistoryRaw.stream()
                .map(entry -> new PriceHistoryEntry(
                        parseDateTime(entry.get("timestamp")),
                        toBigDecimal(entry.get("price"))
                ))
                .toList();

        log.debug("Token analytics assembled for tokenId={}: supply={}, holders={}, priceHistory={} entries",
                tokenId, totalSupply, holders, priceHistory.size());

        return new TokenAnalyticsResponse(totalSupply, circulatingSupply, holders, priceHistory, transferVolume24h);
    }

    /**
     * Scans recent transactions for the AML/risk dashboard and returns those
     * tripping at least one heuristic, with a human-readable reason string.
     *
     * <p>Heuristics, evaluated per transaction:
     * <ol>
     *   <li>a SWAP whose amount exceeds 5% of its pool's TVL (TVL summed from
     *       {@code totalTvlX + totalTvlY} — the pool response has no single
     *       {@code tvl} field; reading one silently disabled this check before);</li>
     *   <li>more than {@link #HIGH_FREQUENCY_THRESHOLD} transactions from the
     *       same user within the scanned batch;</li>
     *   <li>price impact above {@link #PRICE_IMPACT_THRESHOLD}.</li>
     * </ol>
     *
     * <p>Rows already marked reviewed by an admin (non-null {@code reviewedAt})
     * are dropped up front, so acknowledging a row in the UI removes it from the
     * list on the next query. All inputs come from circuit-broken clients, so an
     * outage yields an empty (not failed) result.
     *
     * @return the flagged transactions (possibly empty); never {@code null}
     */
    public List<SuspiciousTransactionResponse> getSuspiciousTransactions() {
        log.debug("Fetching and analyzing transactions for suspicious activity");

        // Sprint 9-DS-r4 (P1-13) — both calls now CB-wrapped.
        List<Map<String, Object>> transactions = downstream.fetchRecentTransactions(1000);

        if (transactions.isEmpty()) {
            log.debug("No transactions found for suspicious activity analysis");
            return Collections.emptyList();
        }

        // Sprint 9-DS-r4 (P2-12) — skip rows already marked-reviewed
        // by an admin (POST /transactions/{id}/review). The flag
        // persists on the transactions table; admin-ui's
        // "Просмотрено" button POSTs to that endpoint and then
        // invalidates this query, so the row disappears from the
        // suspicious list immediately.
        transactions = transactions.stream()
                .filter(tx -> tx.get("reviewedAt") == null)
                .toList();
        if (transactions.isEmpty()) {
            log.debug("All recent transactions already reviewed");
            return Collections.emptyList();
        }

        List<Map<String, Object>> pools = poolEngine.fetchPoolsPage(AGGREGATION_PAGE_SIZE);

        Map<String, Long> poolTvlMap = pools != null
                ? pools.stream().collect(
                        java.util.stream.Collectors.toMap(
                                p -> toString(p.get("id")),
                                // PoolResponse has no "tvl" field — it exposes
                                // totalTvlX/totalTvlY. Reading "tvl" returned 0
                                // for every pool, silently disabling the
                                // "swap > 5% of TVL" suspicious-transaction check.
                                p -> toLong(p.get("totalTvlX")) + toLong(p.get("totalTvlY")),
                                (a, b) -> a
                        ))
                : Collections.emptyMap();

        // Count transactions per user in recent window for frequency analysis
        Map<String, Long> userTxCount = transactions.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        tx -> toString(tx.get("userId")),
                        java.util.stream.Collectors.counting()
                ));

        // High-frequency flags once per user, not once per transaction — else an
        // active account floods the review queue with its entire history.
        java.util.Set<String> freqFlaggedUsers = new java.util.HashSet<>();
        List<SuspiciousTransactionResponse> suspicious = new ArrayList<>();

        for (Map<String, Object> tx : transactions) {
            // Field names per TransactionResponse: txType / amountIn / createdAt
            // (NOT type / amount / timestamp — those read null and silently
            // produced empty/zero flag rows).
            String txType = toString(tx.get("txType"));
            long amount = toLong(tx.get("amountIn"));
            BigDecimal priceImpact = toBigDecimal(tx.get("priceImpact"));
            String userId = toString(tx.get("userId"));
            String poolId = toString(tx.get("poolId"));
            String transactionId = toString(tx.get("id"));

            List<String> reasons = new ArrayList<>();

            // Check 1: Swap > 5% of pool TVL
            if ("SWAP".equalsIgnoreCase(txType)) {
                Long poolTvl = poolTvlMap.get(poolId);
                if (poolTvl != null && poolTvl > 0 && amount > poolTvl * 5 / 100) {
                    reasons.add("Swap exceeds 5% of pool TVL");
                }
            }

            // Check 2: High frequency (> 50 tx per user in the batch) — flagged
            // once per user (Set.add is true only the first time we see them).
            Long count = userTxCount.get(userId);
            if (count != null && count > HIGH_FREQUENCY_THRESHOLD && freqFlaggedUsers.add(userId)) {
                reasons.add("High frequency: " + count + " transactions detected");
            }

            // Check 3: Price impact > 3%
            if (priceImpact.compareTo(PRICE_IMPACT_THRESHOLD) > 0) {
                reasons.add("Price impact " + priceImpact + "% exceeds 3% threshold");
            }

            if (!reasons.isEmpty()) {
                suspicious.add(new SuspiciousTransactionResponse(
                        toUUID(transactionId),
                        toUUID(userId),
                        toUUID(poolId),
                        txType,
                        String.join("; ", reasons),
                        amount,
                        priceImpact,
                        parseDateTime(tx.get("createdAt"))
                ));
            }
        }

        log.debug("Suspicious transaction analysis complete: {} flagged out of {} total",
                suspicious.size(), transactions.size());

        return suspicious;
    }

    // ---- Helper methods ----

    /**
     * Extracts a nested list-of-maps from a decoded JSON map, tolerating absence
     * or a wrong type (the pool-detail document nests its history series this way).
     *
     * @param map the parent map (e.g. a pool-detail document)
     * @param key the key whose value should be a JSON array of objects
     * @return the value cast to {@code List<Map<String,Object>>}, or an empty
     *         list if absent or not a list
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return Collections.emptyList();
    }

    /**
     * Lenient coercion of a decoded JSON value to {@link BigDecimal}.
     *
     * @param value raw value (may be {@code null}, a {@link Number}, or a string)
     * @return the decimal value, or {@link BigDecimal#ZERO} if null/unparseable
     */
    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number number) return BigDecimal.valueOf(number.doubleValue());
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * Lenient coercion of a decoded JSON value to {@code long}.
     *
     * @param value raw value (may be {@code null}, a {@link Number}, or a string)
     * @return the long value, or {@code 0L} if null/unparseable
     */
    private long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Lenient coercion of a decoded JSON value to {@code int}.
     *
     * @param value raw value (may be {@code null}, a {@link Number}, or a string)
     * @return the int value, or {@code 0} if null/unparseable
     */
    private int toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Null-safe {@code toString} that maps {@code null} to the empty string
     * (used so downstream string fields never produce a literal {@code "null"}).
     *
     * @param value raw value (may be {@code null})
     * @return {@code value.toString()}, or {@code ""} when null
     */
    private String toString(Object value) {
        return value != null ? value.toString() : "";
    }

    /**
     * Lenient coercion of a decoded JSON value to {@link UUID}.
     *
     * @param value raw value (may be {@code null}, a {@link UUID}, or a string)
     * @return the UUID, or the all-zero {@code UUID(0,0)} sentinel if
     *         null/unparseable (keeps DTO construction from throwing on a
     *         malformed downstream id)
     */
    private UUID toUUID(Object value) {
        if (value == null) return new UUID(0, 0);
        if (value instanceof UUID uuid) return uuid;
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return new UUID(0, 0);
        }
    }

    /**
     * Lenient coercion of a decoded JSON value to {@link LocalDateTime}.
     *
     * @param value raw value (may be {@code null}, a {@link LocalDateTime}, or an
     *              ISO-8601 string)
     * @return the parsed timestamp, or {@link LocalDateTime#now()} if
     *         null/unparseable (a pragmatic fallback so chart points still plot)
     */
    private LocalDateTime parseDateTime(Object value) {
        if (value == null) return LocalDateTime.now();
        if (value instanceof LocalDateTime ldt) return ldt;
        try {
            return LocalDateTime.parse(value.toString());
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }
}
