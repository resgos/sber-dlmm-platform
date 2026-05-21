package com.sber.dlmm.admin.service;

import com.sber.dlmm.admin.client.BffDownstreamClient;
import com.sber.dlmm.admin.client.PoolEngineClient;
import com.sber.dlmm.admin.dto.DashboardResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins {@link AdminService#getDashboard()} after the Sprint 9-DS-r4
 * (P1-13) refactor to the {@link PoolEngineClient} + {@link
 * BffDownstreamClient} shape:
 *
 *   - parses Spring Data Page-shaped responses ({@code {"content": [...]}})
 *     correctly (the wrapped clients return the {@code content} list
 *     directly, so the service code only does the aggregation);
 *   - degrades to zeros when a downstream returns empty (fallback path)
 *     instead of throwing — other counters still aggregate from the
 *     healthy services;
 *   - reads {@code kycStatus == "VERIFIED"} (matches user-service's
 *     actual key, not the legacy {@code verified} the old code looked at);
 *   - aggregates pool TVL from {@code totalTvlY} and fee revenue from
 *     {@code totalFeesCollectedY} (matches the pool-engine DTO field
 *     names).
 *
 * <p>Mockito mocks are now the right shape — previous tests built the
 * service from raw {@link org.springframework.web.reactive.function.client.WebClient}
 * stubs and used reflection to swap fields. After the refactor those
 * fields are gone; the two wrapped-client beans handle all the HTTP.
 */
class AdminServiceDashboardTest {

    private PoolEngineClient poolEngine;
    private BffDownstreamClient downstream;
    private AdminService adminService;

    @BeforeEach
    void setUp() {
        poolEngine = mock(PoolEngineClient.class);
        downstream = mock(BffDownstreamClient.class);
        adminService = new AdminService(poolEngine, downstream);
    }

    // Today's ISO date prefix — the service filters transactionsToday by
    // createdAt.startsWith(today). Mock data must use today so the
    // assertion lands on a non-zero count.
    private static final String TODAY = LocalDate.now().toString();

    @Test
    void aggregatesHealthyResponsesCorrectly() {
        when(downstream.fetchUsersPage(anyInt())).thenReturn(List.<Map<String, Object>>of(
                Map.of("id", "u1", "kycStatus", "VERIFIED"),
                Map.of("id", "u2", "kycStatus", "VERIFIED"),
                Map.of("id", "u3", "kycStatus", "PENDING"),
                Map.of("id", "u4", "kycStatus", "REJECTED")
        ));
        when(poolEngine.fetchPoolsPage(anyInt())).thenReturn(List.<Map<String, Object>>of(
                Map.of("id", "p1", "status", "ACTIVE",
                        "totalTvlY", 100, "volume24h", 50, "totalFeesCollectedY", 5),
                Map.of("id", "p2", "status", "ACTIVE",
                        "totalTvlY", 200, "volume24h", 80, "totalFeesCollectedY", 8),
                Map.of("id", "p3", "status", "PAUSED",
                        "totalTvlY", 30, "volume24h", 0, "totalFeesCollectedY", 1)
        ));
        when(downstream.fetchTransactionsPage(anyInt())).thenReturn(List.<Map<String, Object>>of(
                Map.of("id", "t1", "createdAt", TODAY + "T10:00:00"),
                Map.of("id", "t2", "createdAt", TODAY + "T11:00:00"),
                Map.of("id", "t3", "createdAt", TODAY + "T12:00:00")
        ));

        DashboardResponse r = adminService.getDashboard();

        assertThat(r.totalUsers()).isEqualTo(4);
        assertThat(r.verifiedUsers()).isEqualTo(2);
        assertThat(r.totalPools()).isEqualTo(3);
        assertThat(r.activePools()).isEqualTo(2);
        assertThat(r.totalTvlRub()).isEqualByComparingTo(new BigDecimal("330"));
        assertThat(r.volume24hRub()).isEqualByComparingTo(new BigDecimal("130"));
        assertThat(r.totalFeesCollectedRub()).isEqualByComparingTo(new BigDecimal("14"));
        assertThat(r.transactionsToday()).isEqualTo(3);
    }

    @Test
    void degradesGracefullyOnSingleDownstreamEmpty() {
        // Pool side returns empty (would happen after CB-OPEN fallback);
        // user + transactions stay healthy.
        when(downstream.fetchUsersPage(anyInt())).thenReturn(List.<Map<String, Object>>of(
                Map.of("id", "u1", "kycStatus", "VERIFIED")
        ));
        when(poolEngine.fetchPoolsPage(anyInt())).thenReturn(List.<Map<String, Object>>of());
        when(downstream.fetchTransactionsPage(anyInt())).thenReturn(List.<Map<String, Object>>of(
                Map.of("id", "t1", "createdAt", TODAY + "T09:00:00")
        ));

        DashboardResponse r = adminService.getDashboard();

        assertThat(r.totalUsers()).isEqualTo(1);
        assertThat(r.verifiedUsers()).isEqualTo(1);
        assertThat(r.totalPools()).isZero();
        assertThat(r.activePools()).isZero();
        assertThat(r.totalTvlRub()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(r.transactionsToday()).isEqualTo(1);
    }

    @Test
    void neverThrowsAndReturnsZerosWhenAllDownstreamEmpty() {
        when(downstream.fetchUsersPage(anyInt())).thenReturn(List.of());
        when(poolEngine.fetchPoolsPage(anyInt())).thenReturn(List.of());
        when(downstream.fetchTransactionsPage(anyInt())).thenReturn(List.of());

        DashboardResponse r = adminService.getDashboard();

        assertThat(r.totalUsers()).isZero();
        assertThat(r.totalPools()).isZero();
        assertThat(r.transactionsToday()).isZero();
        assertThat(r.totalTvlRub()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
