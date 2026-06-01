package com.sber.dlmm.admin.dto;

import java.math.BigDecimal;

/**
 * Top-of-dashboard KPI tile for the admin UI: a single snapshot of platform-wide
 * counts and rouble (₽) totals served by {@code GET /api/v1/admin/dashboard}.
 *
 * <p>Aggregated by {@code AdminService} from several downstream services; on a
 * partial outage the unavailable figures degrade to {@code 0} rather than failing
 * the response.
 *
 * @param totalUsers              total number of registered users (count)
 * @param verifiedUsers           number of users whose KYC status is verified (count)
 * @param totalPools              total number of pools, regardless of state (count)
 * @param activePools             number of pools currently in the active state (count)
 * @param totalTvlRub             total value locked across all pools, in roubles (₽)
 * @param volume24hRub            trailing-24h trading volume across all pools, in roubles (₽)
 * @param totalFeesCollectedRub   cumulative fees ever collected platform-wide, in roubles (₽)
 * @param activePositions         number of currently open liquidity positions (count)
 * @param transactionsToday       number of transactions recorded so far today (count)
 */
public record DashboardResponse(
    long totalUsers,
    long verifiedUsers,
    int totalPools,
    int activePools,
    BigDecimal totalTvlRub,
    BigDecimal volume24hRub,
    BigDecimal totalFeesCollectedRub,
    long activePositions,
    long transactionsToday
) {}
