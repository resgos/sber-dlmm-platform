package com.sber.dlmm.admin.controller;

import com.sber.dlmm.admin.dto.DashboardResponse;
import com.sber.dlmm.admin.dto.PoolAnalyticsResponse;
import com.sber.dlmm.admin.dto.SuspiciousTransactionResponse;
import com.sber.dlmm.admin.dto.TokenAnalyticsResponse;
import com.sber.dlmm.admin.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Backend-for-frontend (BFF) REST controller for the admin UI's dashboard and
 * analytics screens.
 *
 * <p>Every endpoint is read-only ({@code GET}) and is gated to the {@code ADMIN}
 * or {@code SUPER_ADMIN} role via {@code @PreAuthorize}. The actual aggregation
 * work — fanning out to downstream services (user-service, pool-engine,
 * fee-service, transaction-service, token-service, price-oracle), applying
 * Resilience4j circuit breakers, and degrading to empty/zeroed data on a
 * downstream outage — lives in {@link AdminService}; this class only maps HTTP
 * requests onto service calls and wraps the result in a {@link ResponseEntity}.
 *
 * <p>Sibling admin BFF controllers split other concerns out of this file:
 * proxy/pass-through endpoints live in {@code AdminProxyController}, in-process
 * insights in {@code InsightsController}, and retention metrics in
 * {@code CohortController}. This class is kept focused on the dashboard KPI tile
 * and the three per-entity analytics views plus the AML suspicious-transaction
 * feed.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin BFF", description = "Admin dashboard and analytics endpoints")
public class AdminController {

    private final AdminService adminService;

    /**
     * Creates the controller with the aggregation service injected by Spring.
     *
     * @param adminService service that performs the downstream fan-out,
     *                      circuit-breaking and aggregation for every endpoint here
     */
    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    /**
     * Returns the top-of-dashboard KPI tile: platform-wide counts and ₽ totals
     * (users, pools, TVL, 24h volume, fees collected, active positions and
     * transactions today), aggregated across several downstream services.
     *
     * <p>Resilient by design — each downstream call is circuit-breaker guarded
     * and its fallback degrades to empty/zeroed data, so the response always has
     * a valid {@link DashboardResponse} shape even during a partial outage.
     *
     * @return {@code 200 OK} wrapping the aggregated {@link DashboardResponse}
     */
    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get admin dashboard",
            description = "Admin backend-for-frontend endpoint that aggregates dashboard KPIs "
                    + "(users, pools, TVL, 24h volume, fees, active positions, transactions today) "
                    + "from user-service, pool-engine and transaction-service. Each downstream call is "
                    + "guarded by a Resilience4j circuit breaker whose fallback degrades to empty data, "
                    + "so the response always has a valid shape even during a downstream outage.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Dashboard data aggregated successfully"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<DashboardResponse> getDashboard() {
        return ResponseEntity.ok(adminService.getDashboard());
    }

    /**
     * Returns 30-day analytics for a single pool — TVL history, volume history,
     * fee history, current bin liquidity distribution and the top liquidity
     * providers — for the admin pool-detail analytics view.
     *
     * <p>Aggregated from pool-engine and fee-service; each downstream call is
     * circuit-breaker guarded and falls back to empty series on an outage rather
     * than failing the whole request.
     *
     * @param id the pool's UUID, taken from the path
     * @return {@code 200 OK} wrapping the per-pool {@link PoolAnalyticsResponse}
     */
    @GetMapping("/pools/{id}/analytics")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get pool analytics",
            description = "Admin backend-for-frontend endpoint returning 30-day analytics for a single pool "
                    + "(TVL history, volume history, fee history, bin distribution, top LPs), aggregated from "
                    + "pool-engine and fee-service. Each downstream call is circuit-breaker guarded and falls "
                    + "back to empty series on a downstream outage rather than failing the request.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pool analytics aggregated successfully"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<PoolAnalyticsResponse> getPoolAnalytics(
            @Parameter(description = "Pool identifier (UUID)") @PathVariable UUID id) {
        return ResponseEntity.ok(adminService.getPoolAnalytics(id));
    }

    /**
     * Returns analytics for a single token — total and circulating supply,
     * holder count, 24h transfer volume and 30-day price history — for the admin
     * token-detail analytics view.
     *
     * <p>Aggregated from token-service and price-oracle; each downstream call is
     * circuit-breaker guarded and degrades to zeros/empty history on an outage
     * rather than failing the whole request.
     *
     * @param id the token's UUID, taken from the path
     * @return {@code 200 OK} wrapping the per-token {@link TokenAnalyticsResponse}
     */
    @GetMapping("/tokens/{id}/analytics")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get token analytics",
            description = "Admin backend-for-frontend endpoint returning token supply, circulating supply, "
                    + "holder count, 24h transfer volume and 30-day price history, aggregated from token-service "
                    + "and price-oracle. Each downstream call is circuit-breaker guarded and degrades to "
                    + "zeros/empty history on a downstream outage rather than failing the request.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token analytics aggregated successfully"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<TokenAnalyticsResponse> getTokenAnalytics(
            @Parameter(description = "Token identifier (UUID)") @PathVariable UUID id) {
        return ResponseEntity.ok(adminService.getTokenAnalytics(id));
    }

    /**
     * Returns the AML suspicious-transaction feed for the admin review queue:
     * recent transactions flagged by heuristics (a swap exceeding 5% of pool
     * TVL, a user with more than 50 transactions in the scanned batch, or price
     * impact above 3%). Rows already marked reviewed are excluded.
     *
     * <p>Source data is fetched from transaction-service and pool-engine via
     * circuit-breaker guarded clients; an empty list is returned when no data is
     * available or nothing trips a heuristic.
     *
     * @return {@code 200 OK} wrapping the (possibly empty) list of
     *         {@link SuspiciousTransactionResponse} rows
     */
    @GetMapping("/transactions/suspicious")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get suspicious transactions",
            description = "Admin backend-for-frontend endpoint that scans recent transactions and flags ones "
                    + "matching AML heuristics: a swap exceeding 5% of pool TVL, a user with more than 50 "
                    + "transactions in the batch, or price impact above 3%. Already-reviewed rows are excluded. "
                    + "Source data is fetched from transaction-service and pool-engine via circuit-breaker "
                    + "guarded clients; an empty list is returned when no data is available.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Flagged transactions returned (possibly empty)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<List<SuspiciousTransactionResponse>> getSuspiciousTransactions() {
        return ResponseEntity.ok(adminService.getSuspiciousTransactions());
    }
}
