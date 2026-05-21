package com.sber.dlmm.common.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Sprint 9-DS-r4 (P2-13) — moved from {@code dlmm-user-service} to
 * {@code dlmm-common.audit}. Not annotated @Repository so
 * {@link DlmmAdminAuditAutoConfiguration} can register it explicitly
 * via @EnableJpaRepositories — keeps the consuming service's JPA
 * configuration from accidentally picking it up via package scan.
 */
public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, UUID> {

    /** Recent audits — admin dashboard list view. */
    Page<AdminAuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Drill-down: every action by one admin user. */
    Page<AdminAuditLog> findByActorUserIdOrderByCreatedAtDesc(UUID actorUserId, Pageable pageable);

    /** Drill-down: every audit touching a specific target. */
    Page<AdminAuditLog> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
            String targetType, String targetId, Pageable pageable);
}
