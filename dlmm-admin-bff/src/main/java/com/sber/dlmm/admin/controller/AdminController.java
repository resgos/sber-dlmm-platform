package com.sber.dlmm.admin.controller;

import com.sber.dlmm.admin.dto.DashboardResponse;
import com.sber.dlmm.admin.dto.PoolAnalyticsResponse;
import com.sber.dlmm.admin.dto.SuspiciousTransactionResponse;
import com.sber.dlmm.admin.dto.TokenAnalyticsResponse;
import com.sber.dlmm.admin.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin BFF", description = "Admin dashboard and analytics endpoints")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get admin dashboard", description = "Aggregated dashboard data from all microservices")
    public ResponseEntity<DashboardResponse> getDashboard() {
        return ResponseEntity.ok(adminService.getDashboard());
    }

    @GetMapping("/pools/{id}/analytics")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get pool analytics", description = "30-day analytics for a specific pool including TVL, volume, fees, bin distribution, and top LPs")
    public ResponseEntity<PoolAnalyticsResponse> getPoolAnalytics(@PathVariable UUID id) {
        return ResponseEntity.ok(adminService.getPoolAnalytics(id));
    }

    @GetMapping("/tokens/{id}/analytics")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get token analytics", description = "Token supply, holders, and price history analytics")
    public ResponseEntity<TokenAnalyticsResponse> getTokenAnalytics(@PathVariable UUID id) {
        return ResponseEntity.ok(adminService.getTokenAnalytics(id));
    }

    @GetMapping("/transactions/suspicious")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "Get suspicious transactions", description = "Transactions flagged for: swaps > 5% TVL, > 50 tx/min per user, price impact > 3%")
    public ResponseEntity<List<SuspiciousTransactionResponse>> getSuspiciousTransactions() {
        return ResponseEntity.ok(adminService.getSuspiciousTransactions());
    }
}
