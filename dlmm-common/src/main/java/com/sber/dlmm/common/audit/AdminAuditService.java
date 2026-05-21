package com.sber.dlmm.common.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Sprint 9-DS-r4 (P2-13) — moved from {@code dlmm-user-service} into
 * {@code dlmm-common.audit} so every service can wire the same
 * writer (transaction-service, pool-engine, …) without copy-paste.
 *
 * <p>Write path is invoked by {@link AdminAuditAspect}. Marked
 * {@link Propagation#REQUIRES_NEW} so the audit row survives even if
 * the surrounding business transaction rolls back — auditing a FAILED
 * attempt is the whole point.
 *
 * <p>Read path is exposed via user-service's {@code UserController.getAuditLog}
 * (SUPER_ADMIN-only, paged); other services typically only write.
 */
public class AdminAuditService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditService.class);

    private final AdminAuditLogRepository repository;

    public AdminAuditService(AdminAuditLogRepository repository) {
        this.repository = repository;
    }

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
