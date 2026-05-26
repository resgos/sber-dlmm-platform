package com.sber.dlmm.admin.service;

import com.sber.dlmm.admin.dto.AuditLogEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * QW-5 (Batch #5, Sprint 14) — admin audit-log query service.
 *
 * <p>Reads from {@code admin_audit_log} table (populated by
 * {@code @AdminAudit} + {@code @UserAudit} aspects in dlmm-common.audit).
 * Supports filtering by target — used by the activity-log sidebar on
 * admin entity detail pages ("show me every action that touched this
 * transaction / pool / user").
 *
 * <p>Read-only, paged (default 20). Same Postgres datasource as
 * {@link CohortService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final JdbcTemplate jdbc;

    /**
     * Query audit-log entries filtered by target. Both {@code targetType}
     * and {@code targetId} are required — there's no "all audit log"
     * scan endpoint (we don't want to expose unbounded audit dumps via
     * a single GET).
     */
    public List<AuditLogEntry> findByTarget(String targetType, String targetId, int limit) {
        if (targetType == null || targetType.isBlank() || targetId == null || targetId.isBlank()) {
            return Collections.emptyList();
        }
        int safeLimit = Math.max(1, Math.min(limit, 200));

        String sql = """
                SELECT id, action, actor_type, actor_user_id, actor_role,
                       target_type, target_id, status, error_message,
                       method_signature, ip_address, created_at
                FROM admin_audit_log
                WHERE target_type = ? AND target_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """;

        try {
            return jdbc.query(sql, (rs, rowNum) -> new AuditLogEntry(
                    rs.getObject("id", java.util.UUID.class),
                    rs.getString("action"),
                    rs.getString("actor_type"),
                    rs.getObject("actor_user_id", java.util.UUID.class),
                    rs.getString("actor_role"),
                    rs.getString("target_type"),
                    rs.getString("target_id"),
                    rs.getString("status"),
                    rs.getString("error_message"),
                    rs.getString("method_signature"),
                    rs.getString("ip_address"),
                    rs.getTimestamp("created_at") != null
                            ? rs.getTimestamp("created_at").toLocalDateTime()
                            : null
            ), targetType, targetId, safeLimit);
        } catch (Exception ex) {
            log.warn("AuditLogService.findByTarget({}, {}, {}) failed: {}",
                    targetType, targetId, safeLimit, ex.getMessage());
            return Collections.emptyList();
        }
    }
}
