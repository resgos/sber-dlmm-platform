package com.sber.dlmm.user.repository;

import com.sber.dlmm.user.entity.AdminAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, UUID> {

    /** Recent audits — admin dashboard list view. */
    Page<AdminAuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Drill-down: every action by one admin user. */
    Page<AdminAuditLog> findByActorUserIdOrderByCreatedAtDesc(UUID actorUserId, Pageable pageable);

    /** Drill-down: every audit touching a specific target. */
    Page<AdminAuditLog> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(
            String targetType, String targetId, Pageable pageable);
}
