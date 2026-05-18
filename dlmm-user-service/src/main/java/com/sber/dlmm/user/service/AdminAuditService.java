package com.sber.dlmm.user.service;

import com.sber.dlmm.user.entity.AdminAuditLog;
import com.sber.dlmm.user.repository.AdminAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Sprint 8 #AU-4 — admin audit log writer + reader (audit C-6).
 *
 * <p>Write path is invoked by {@code AdminAuditAspect} (around-advice on
 * methods annotated with {@code @AdminAudit}). Marked
 * {@link Propagation#REQUIRES_NEW} so the audit row survives even if the
 * surrounding business transaction rolls back — auditing a FAILED attempt
 * is the whole point.
 *
 * <p>Read path is exposed via {@code UserController.getAuditLog} —
 * SUPER_ADMIN-only, paged. Cross-service audits (when other services
 * adopt @AdminAudit) will need either their own writer or a remote API
 * to this one — Sprint 9+ design decision.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminAuditService {

    private final AdminAuditLogRepository repository;

    /**
     * Persist a single audit row. {@link Propagation#REQUIRES_NEW} so this
     * commits even if the caller's transaction rolls back — we want the
     * FAILED audit row to survive the failure that caused the rollback.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AdminAuditLog row) {
        try {
            repository.save(row);
        } catch (Exception ex) {
            // Audit-write failures must not break the business path. Log
            // loudly so we notice — but the original action stays committed.
            log.error("Failed to persist admin audit row action={} actor={} target={}/{}: {}",
                    row.getAction(), row.getActorUserId(), row.getTargetType(), row.getTargetId(),
                    ex.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Page<AdminAuditLog> recent(int page, int size) {
        return repository.findAllByOrderByCreatedAtDesc(pageable(page, size));
    }

    @Transactional(readOnly = true)
    public Page<AdminAuditLog> byActor(UUID actorUserId, int page, int size) {
        return repository.findByActorUserIdOrderByCreatedAtDesc(actorUserId, pageable(page, size));
    }

    @Transactional(readOnly = true)
    public Page<AdminAuditLog> byTarget(String targetType, String targetId, int page, int size) {
        return repository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
                targetType, targetId, pageable(page, size));
    }

    private static Pageable pageable(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 200));
    }
}
