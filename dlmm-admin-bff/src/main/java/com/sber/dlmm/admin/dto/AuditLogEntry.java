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
 *
 * @param id              primary key of the audit-log row
 * @param action          action that was audited (e.g. {@code POOL_PAUSE})
 * @param actorType       kind of actor: {@code ADMIN} or {@code USER}
 * @param actorUserId     identifier of the human actor, or {@code null} for
 *                        system/bootstrap entries
 * @param actorRole       role the actor held when performing the action
 * @param targetType      type of the affected entity (e.g. {@code POOL},
 *                        {@code USER}, {@code TOKEN})
 * @param targetId        identifier of the affected entity
 * @param status          outcome of the action (e.g. {@code SUCCESS},
 *                        {@code FAILURE})
 * @param errorMessage    failure detail when the action errored, otherwise {@code null}
 * @param methodSignature signature of the handler method that performed the action
 * @param ipAddress       source IP address of the request
 * @param createdAt       time the audited action occurred
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
