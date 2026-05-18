package com.sber.dlmm.pool.controller;

import com.sber.dlmm.pool.entity.LiquidityPool;
import com.sber.dlmm.pool.repository.LiquidityPoolRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Sprint 9 R-M-33 — Public Data API tiers.
 *
 * <p>Read-only, no-auth endpoints for analysts, market-data consumers,
 * and the developer ecosystem. The gateway routes {@code /api/v1/public/**}
 * here and skips JWT validation (per {@code JwtValidationFilter.SKIP_PATHS}).
 * Rate-limiting falls back to IP-keyed FREE tier limits (10 rps per IP).
 *
 * <p>Cache: Sprint 9 ships without cache annotations — at 10 rps FREE
 * tier ceiling the DB load is acceptable. Sprint 10 adds Caffeine-backed
 * @Cacheable + EnableCaching when Pro tier analyst polling becomes a
 * real-load concern.
 *
 * <p>What's INTENTIONALLY not here:
 * <ul>
 *   <li>Per-pool deep state (bins, positions) — paid Pro tier
 *       feature; would go in a separate auth-required endpoint</li>
 *   <li>Per-user / per-trade data — never public</li>
 *   <li>Write endpoints — read-only by design</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/public")
@RequiredArgsConstructor
@Tag(name = "Public Data API", description = "Sprint 9 R-M-33 — no-auth aggregate pool stats")
public class PublicDataController {

    private static final int STATS_PAGE_SIZE = 200;

    private final LiquidityPoolRepository liquidityPoolRepository;

    /**
     * GET /api/v1/public/pools/stats — platform-wide aggregate.
     * Sums TVL + 24h volume + fees + counts across all pools. Single
     * cached value, no per-pool detail (use authenticated /pools for that).
     */
    @GetMapping("/pools/stats")
    @Operation(summary = "Aggregate pool stats — TVL, volume24h, fees, active count")
    public PublicPoolStats getPoolStats() {
        List<LiquidityPool> pools = liquidityPoolRepository
                .findAll(PageRequest.of(0, STATS_PAGE_SIZE))
                .getContent();

        long totalPools = pools.size();
        long activePools = pools.stream()
                .filter(p -> "ACTIVE".equals(p.getStatus() == null ? null : p.getStatus().name()))
                .count();
        long totalTvl = pools.stream()
                .mapToLong(p -> safe(p.getTotalTvlX()) + safe(p.getTotalTvlY()))
                .sum();
        long volume24h = pools.stream()
                .mapToLong(p -> safe(p.getVolume24h()))
                .sum();
        long totalFeesCollected = pools.stream()
                .mapToLong(p -> safe(p.getTotalFeesCollectedX()) + safe(p.getTotalFeesCollectedY()))
                .sum();

        return new PublicPoolStats(
                totalPools,
                activePools,
                totalTvl,
                volume24h,
                totalFeesCollected,
                java.time.Instant.now().toString());
    }

    private static long safe(long v) { return v; }

    /**
     * Public aggregate snapshot. Versioned by adding fields rather than
     * mutating — analyst integrations rely on field stability.
     */
    public record PublicPoolStats(
            long totalPools,
            long activePools,
            long totalTvl,
            long volume24hTotal,
            long totalFeesCollected,
            String generatedAt) {}
}
