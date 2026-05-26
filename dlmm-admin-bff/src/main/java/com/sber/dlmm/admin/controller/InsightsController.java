package com.sber.dlmm.admin.controller;

import com.sber.dlmm.admin.dto.AuditLogEntry;
import com.sber.dlmm.admin.dto.CustomerSuccessWeekly;
import com.sber.dlmm.admin.dto.PilotHealth;
import com.sber.dlmm.admin.service.AuditLogService;
import com.sber.dlmm.admin.service.CustomerSuccessService;
import com.sber.dlmm.admin.service.PilotHealthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Batch #5 — admin insights endpoints.
 *
 * <ul>
 *   <li><b>B-04</b> {@code GET /admin/cs/weekly} — customer-success snapshot</li>
 *   <li><b>B-06</b> {@code GET /admin/pilots/health} — per-org engagement scores</li>
 *   <li><b>QW-5</b> {@code GET /admin/audit-log?targetType=&targetId=&limit=} —
 *       activity-log sidebar data source</li>
 * </ul>
 *
 * All endpoints ADMIN/SUPER_ADMIN-gated. Lives outside AdminController
 * to keep that file focused on dashboard/analytics aggregations.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin Insights", description = "CS metrics, pilot health, audit-log query")
@RequiredArgsConstructor
public class InsightsController {

    private final CustomerSuccessService customerSuccess;
    private final PilotHealthService pilotHealth;
    private final AuditLogService auditLog;

    @GetMapping("/cs/weekly")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "B-04 — weekly customer-success snapshot for PO")
    public ResponseEntity<CustomerSuccessWeekly> getWeeklySnapshot() {
        return ResponseEntity.ok(customerSuccess.getWeeklySnapshot());
    }

    @GetMapping("/pilots/health")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "B-06 — per-org pilot health scores")
    public ResponseEntity<List<PilotHealth>> getPilotHealth() {
        return ResponseEntity.ok(pilotHealth.getAllPilotHealth());
    }

    @GetMapping("/audit-log")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "QW-5 — audit-log entries for an entity (activity-log sidebar)")
    public ResponseEntity<List<AuditLogEntry>> getAuditLog(
            @RequestParam String targetType,
            @RequestParam String targetId,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(auditLog.findByTarget(targetType, targetId, limit));
    }
}
