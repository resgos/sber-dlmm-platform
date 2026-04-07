package com.sber.dlmm.admin.service;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class AdminService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final BigDecimal PRICE_IMPACT_THRESHOLD = new BigDecimal("3.0");
    private static final long HIGH_FREQUENCY_THRESHOLD = 50;

    private final WebClient userServiceClient;
    private final WebClient tokenServiceClient;
    private final WebClient poolEngineClient;
    private final WebClient feeServiceClient;
    private final WebClient transactionServiceClient;
    private final WebClient priceOracleClient;

    public AdminService(
            WebClient.Builder webClientBuilder,
            @Value("${dlmm.services.user-service-url}") String userServiceUrl,
            @Value("${dlmm.services.token-service-url}") String tokenServiceUrl,
            @Value("${dlmm.services.pool-engine-url}") String poolEngineUrl,
            @Value("${dlmm.services.fee-service-url}") String feeServiceUrl,
            @Value("${dlmm.services.transaction-service-url}") String transactionServiceUrl,
            @Value("${dlmm.services.price-oracle-url}") String priceOracleUrl
    ) {
        this.userServiceClient = webClientBuilder.clone().baseUrl(userServiceUrl).build();
        this.tokenServiceClient = webClientBuilder.clone().baseUrl(tokenServiceUrl).build();
        this.poolEngineClient = webClientBuilder.clone().baseUrl(poolEngineUrl).build();
        this.feeServiceClient = webClientBuilder.clone().baseUrl(feeServiceUrl).build();
        this.transactionServiceClient = webClientBuilder.clone().baseUrl(transactionServiceUrl).build();
        this.priceOracleClient = webClientBuilder.clone().baseUrl(priceOracleUrl).build();
    }

    public DashboardResponse getDashboard() {
        log.debug("Fetching dashboard data from all microservices");

        Mono<List<Map<String, Object>>> usersMono = userServiceClient.get()
                .uri("/api/v1/users")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .onErrorReturn(Collections.emptyList());

        Mono<List<Map<String, Object>>> poolsMono = poolEngineClient.get()
                .uri("/api/v1/pools")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .onErrorReturn(Collections.emptyList());

        Mono<List<Map<String, Object>>> transactionsMono = transactionServiceClient.get()
                .uri("/api/v1/transactions")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .onErrorReturn(Collections.emptyList());

        var combined = Mono.zip(usersMono, poolsMono, transactionsMono);
        var tuple = combined.block(REQUEST_TIMEOUT);

        if (tuple == null) {
            log.warn("Timed out waiting for dashboard data, returning defaults");
            return new DashboardResponse(0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
        }

        List<Map<String, Object>> users = tuple.getT1();
        List<Map<String, Object>> pools = tuple.getT2();
        List<Map<String, Object>> transactions = tuple.getT3();

        long totalUsers = users.size();
        long verifiedUsers = users.stream()
                .filter(u -> Boolean.TRUE.equals(u.get("verified")) || Boolean.TRUE.equals(u.get("kycVerified")))
                .count();

        int totalPools = pools.size();
        int activePools = (int) pools.stream()
                .filter(p -> "ACTIVE".equals(p.get("status")))
                .count();

        BigDecimal totalTvlRub = pools.stream()
                .map(p -> toBigDecimal(p.get("tvl")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal volume24hRub = pools.stream()
                .map(p -> toBigDecimal(p.get("volume24h")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalFeesCollectedRub = pools.stream()
                .map(p -> toBigDecimal(p.get("totalFees")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long activePositions = pools.stream()
                .mapToLong(p -> toLong(p.get("activePositions")))
                .sum();

        long transactionsToday = transactions.size();

        DashboardResponse response = new DashboardResponse(
                totalUsers, verifiedUsers, totalPools, activePools,
                totalTvlRub, volume24hRub, totalFeesCollectedRub,
                activePositions, transactionsToday
        );

        log.debug("Dashboard data assembled: totalUsers={}, totalPools={}, transactionsToday={}",
                totalUsers, totalPools, transactionsToday);

        return response;
    }

    public PoolAnalyticsResponse getPoolAnalytics(UUID poolId) {
        log.debug("Fetching pool analytics for poolId={}", poolId);

        Mono<Map<String, Object>> poolDetailMono = poolEngineClient.get()
                .uri("/api/v1/pools/{id}", poolId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .onErrorReturn(Collections.emptyMap());

        Mono<List<Map<String, Object>>> feeHistoryMono = feeServiceClient.get()
                .uri("/api/v1/fees/pool/{poolId}/history?days=30", poolId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .onErrorReturn(Collections.emptyList());

        var combined = Mono.zip(poolDetailMono, feeHistoryMono);
        var tuple = combined.block(REQUEST_TIMEOUT);

        if (tuple == null) {
            log.warn("Timed out waiting for pool analytics data for poolId={}", poolId);
            return new PoolAnalyticsResponse(
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList()
            );
        }

        Map<String, Object> poolDetail = tuple.getT1();
        List<Map<String, Object>> feeHistory = tuple.getT2();

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

    public TokenAnalyticsResponse getTokenAnalytics(UUID tokenId) {
        log.debug("Fetching token analytics for tokenId={}", tokenId);

        Mono<Map<String, Object>> tokenDetailMono = tokenServiceClient.get()
                .uri("/api/v1/tokens/{id}", tokenId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .onErrorReturn(Collections.emptyMap());

        Mono<List<Map<String, Object>>> priceHistoryMono = priceOracleClient.get()
                .uri("/api/v1/prices/{tokenId}/history?days=30", tokenId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .onErrorReturn(Collections.emptyList());

        var combined = Mono.zip(tokenDetailMono, priceHistoryMono);
        var tuple = combined.block(REQUEST_TIMEOUT);

        if (tuple == null) {
            log.warn("Timed out waiting for token analytics data for tokenId={}", tokenId);
            return new TokenAnalyticsResponse(0, 0, 0, Collections.emptyList(), 0);
        }

        Map<String, Object> tokenDetail = tuple.getT1();
        List<Map<String, Object>> priceHistoryRaw = tuple.getT2();

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

    public List<SuspiciousTransactionResponse> getSuspiciousTransactions() {
        log.debug("Fetching and analyzing transactions for suspicious activity");

        List<Map<String, Object>> transactions = transactionServiceClient.get()
                .uri("/api/v1/transactions?limit=1000")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .onErrorReturn(Collections.emptyList())
                .block(REQUEST_TIMEOUT);

        if (transactions == null || transactions.isEmpty()) {
            log.debug("No transactions found for suspicious activity analysis");
            return Collections.emptyList();
        }

        List<Map<String, Object>> pools = poolEngineClient.get()
                .uri("/api/v1/pools")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .onErrorReturn(Collections.emptyList())
                .block(REQUEST_TIMEOUT);

        Map<String, Long> poolTvlMap = pools != null
                ? pools.stream().collect(
                        java.util.stream.Collectors.toMap(
                                p -> toString(p.get("id")),
                                p -> toLong(p.get("tvl")),
                                (a, b) -> a
                        ))
                : Collections.emptyMap();

        // Count transactions per user in recent window for frequency analysis
        Map<String, Long> userTxCount = transactions.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        tx -> toString(tx.get("userId")),
                        java.util.stream.Collectors.counting()
                ));

        List<SuspiciousTransactionResponse> suspicious = new ArrayList<>();

        for (Map<String, Object> tx : transactions) {
            String txType = toString(tx.get("type"));
            long amount = toLong(tx.get("amount"));
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

            // Check 2: High frequency (> 50 tx per user in the batch)
            Long count = userTxCount.get(userId);
            if (count != null && count > HIGH_FREQUENCY_THRESHOLD) {
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
                        String.join("; ", reasons),
                        amount,
                        priceImpact,
                        parseDateTime(tx.get("timestamp"))
                ));
            }
        }

        log.debug("Suspicious transaction analysis complete: {} flagged out of {} total",
                suspicious.size(), transactions.size());

        return suspicious;
    }

    // ---- Helper methods ----

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return Collections.emptyList();
    }

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

    private long toLong(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private int toInt(Object value) {
        if (value == null) return 0;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String toString(Object value) {
        return value != null ? value.toString() : "";
    }

    private UUID toUUID(Object value) {
        if (value == null) return new UUID(0, 0);
        if (value instanceof UUID uuid) return uuid;
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return new UUID(0, 0);
        }
    }

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
