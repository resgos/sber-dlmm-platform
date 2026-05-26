package com.sber.dlmm.admin.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * QW-5 (Batch #5, Sprint 14) — single row from admin_audit_log,
 * surfaced to the admin UI activity-log sidebar.
 *
 * <p>Direct projection of the table columns + actor_type from S13-02.
 * actorUserId is nullable because some entries (system / bootstrap)
 * have no human actor; the UI shows "Система" в этом случае.
 */
public record AuditLogEntry(
        UUID id,
        String action,
        String actorType,        // ADMIN | USER (added by S13-02)
        UUID actorUserId,
        String actorRole,
        String targetType,
        String targetId,
        String status,
        String errorMessage,
        String methodSignature,
        String ipAddress,
        LocalDateTime createdAt
) {}
