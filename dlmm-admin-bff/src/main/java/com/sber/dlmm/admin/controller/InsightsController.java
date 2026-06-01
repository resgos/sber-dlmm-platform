package com.sber.dlmm.admin.controller;

import com.sber.dlmm.admin.dto.AuditLogEntry;
import com.sber.dlmm.admin.dto.CustomerSuccessWeekly;
import com.sber.dlmm.admin.dto.PilotHealth;
import com.sber.dlmm.admin.service.AuditLogService;
import com.sber.dlmm.admin.service.CustomerSuccessService;
import com.sber.dlmm.admin.service.PilotHealthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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

    /**
     * Returns the weekly customer-success snapshot (B-04) consumed by the
     * product owner — active orgs/users, new signups, transaction and fee
     * totals, 2FA/KYC adoption, top fee-yielding orgs and churn warnings for the
     * trailing 7 days.
     *
     * <p>Computed in-process from the shared database by
     * {@code CustomerSuccessService}; no downstream service calls are involved.
     *
     * @return {@code 200 OK} wrapping the {@link CustomerSuccessWeekly} snapshot
     */
    @GetMapping("/cs/weekly")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "B-04 — weekly customer-success snapshot for PO",
            description = "Admin backend-for-frontend endpoint returning the weekly customer-success snapshot "
                    + "(B-04) consumed by the product owner. Computed in-process from the shared database; "
                    + "no downstream service calls are involved.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Weekly customer-success snapshot returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<CustomerSuccessWeekly> getWeeklySnapshot() {
        return ResponseEntity.ok(customerSuccess.getWeeklySnapshot());
    }

    /**
     * Returns per-organisation pilot engagement/health scores (B-06) for the
     * admin pilot-health dashboard — one {@link PilotHealth} row per pilot org,
     * each with a 0–100 engagement score, a health flag and suggested next steps.
     *
     * <p>Computed in-process from the shared database by
     * {@code PilotHealthService}; no downstream service calls are involved.
     *
     * @return {@code 200 OK} wrapping the list of per-org {@link PilotHealth} rows
     */
    @GetMapping("/pilots/health")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "B-06 — per-org pilot health scores",
            description = "Admin backend-for-frontend endpoint returning per-organisation pilot engagement/health "
                    + "scores (B-06). Computed in-process from the shared database; no downstream service calls "
                    + "are involved.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Per-org pilot health scores returned"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<List<PilotHealth>> getPilotHealth() {
        return ResponseEntity.ok(pilotHealth.getAllPilotHealth());
    }

    /**
     * Returns the most recent audit-log entries for a single entity (QW-5),
     * powering the activity-log sidebar in the admin UI.
     *
     * <p>Queried directly from the shared database by {@code AuditLogService};
     * no downstream service calls are involved.
     *
     * @param targetType type of the audited entity, e.g. {@code POOL},
     *                   {@code USER}, {@code TOKEN}
     * @param targetId   identifier of the audited entity
     * @param limit      maximum number of entries to return (defaults to 20)
     * @return {@code 200 OK} wrapping the entity's {@link AuditLogEntry} rows,
     *         newest first
     */
    @GetMapping("/audit-log")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    @Operation(summary = "QW-5 — audit-log entries for an entity (activity-log sidebar)",
            description = "Admin backend-for-frontend endpoint returning the most recent audit-log entries for a "
                    + "single entity (QW-5), powering the activity-log sidebar. Queried directly from the shared "
                    + "database; no downstream service calls are involved.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Audit-log entries for the entity returned"),
            @ApiResponse(responseCode = "400", description = "Required query parameter (targetType or targetId) is missing"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the ADMIN or SUPER_ADMIN role")
    })
    public ResponseEntity<List<AuditLogEntry>> getAuditLog(
            @Parameter(description = "Type of the audited entity, e.g. POOL, USER, TOKEN")
            @RequestParam String targetType,
            @Parameter(description = "Identifier of the audited entity")
            @RequestParam String targetId,
            @Parameter(description = "Maximum number of entries to return (default 20)")
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(auditLog.findByTarget(targetType, targetId, limit));
    }
}
