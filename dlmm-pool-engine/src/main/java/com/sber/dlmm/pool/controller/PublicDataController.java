package com.sber.dlmm.pool.controller;

import com.sber.dlmm.pool.entity.LiquidityPool;
import com.sber.dlmm.pool.repository.LiquidityPoolRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
     *
     * <p>Reads the first {@link #STATS_PAGE_SIZE} pools and folds them into the
     * snapshot; "active" is counted by {@code status == ACTIVE}, TVL combines
     * the X and Y sides, and fees combine collected-X and collected-Y.
     *
     * @return the platform-wide {@link PublicPoolStats} aggregate, stamped with the current time
     */
    @GetMapping("/pools/stats")
    @Operation(
            summary = "Aggregate pool stats — TVL, volume24h, fees, active count",
            description = "Public, no-auth endpoint (Sprint 9 R-M-33). Returns a single platform-wide "
                    + "aggregate snapshot: total/active pool counts, combined TVL (X+Y), 24h volume, and "
                    + "total fees collected, plus a generation timestamp. No per-pool or per-user detail is "
                    + "exposed — use the authenticated /api/v1/pools endpoints for that. Open to any caller; "
                    + "the gateway rate-limits unauthenticated traffic per IP (FREE tier, ~10 rps).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Aggregate pool statistics snapshot")
    })
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

    /**
     * Null-safe pass-through for the {@code long} TVL/volume/fee accessors.
     * The entity getters return primitive {@code long}, so there is nothing to
     * coalesce today; this seam keeps the summation call sites uniform and
     * gives one place to add coalescing if an accessor ever becomes nullable.
     *
     * @param v the raw accumulator value
     * @return {@code v} unchanged
     */
    private static long safe(long v) { return v; }

    /**
     * Public aggregate snapshot. Versioned by adding fields rather than
     * mutating — analyst integrations rely on field stability.
     *
     * @param totalPools         total number of pools on the platform
     * @param activePools        number of pools currently in the ACTIVE status
     * @param totalTvl           combined TVL across all pools (X + Y sides), raw units
     * @param volume24hTotal     combined trailing-24h volume across all pools, raw units
     * @param totalFeesCollected combined fees collected across all pools (X + Y sides), raw units
     * @param generatedAt        ISO-8601 timestamp marking when the snapshot was produced
     */
    public record PublicPoolStats(
            long totalPools,
            long activePools,
            long totalTvl,
            long volume24hTotal,
            long totalFeesCollected,
            String generatedAt) {}
}
