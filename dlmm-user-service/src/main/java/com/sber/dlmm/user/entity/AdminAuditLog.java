package com.sber.dlmm.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 8 #AU-4 — admin audit log (audit C-6 critical).
 *
 * <p>Append-only record of every admin mutation that runs through an
 * {@code @AdminAudit}-annotated method. Captured by {@code AdminAuditAspect}
 * via Spring AOP — no caller-side bookkeeping required.
 *
 * <p>Never updated, never deleted. Compliance replay relies on
 * immutability. JPA mapping omits @PreUpdate / setters for written-once
 * fields to make accidental mutation harder.
 */
@Entity
@Table(name = "admin_audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminAuditLog {

    public enum Status { SUCCESS, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** UUID of the admin user who triggered the action. Null only for reserved system audits. */
    @Column(name = "actor_user_id")
    private UUID actorUserId;

    /** "ROLE_ADMIN" or "ROLE_SUPER_ADMIN" — pulled from JWT authorities at write time. */
    @Column(name = "actor_role", length = 40)
    private String actorRole;

    /**
     * Action label from {@code @AdminAudit#value()}, e.g. "USER_BLOCK",
     * "USER_KYC_UPDATE". Stable identifier for analyst queries.
     */
    @Column(nullable = false, length = 60)
    private String action;

    /** "USER" / "POOL" / "TOKEN" — coarse-grained for filters. */
    @Column(name = "target_type", length = 40)
    private String targetType;

    /** Stringified UUID, symbol, or composite ID of the target object. */
    @Column(name = "target_id", length = 120)
    private String targetId;

    @Column(nullable = false, length = 20)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Status status;

    /** Truncated exception message when {@link Status#FAILED}; null on SUCCESS. */
    @Column(name = "error_message", length = 500)
    private String errorMessage;

    /** Class.method — useful when same action label fires from multiple call sites. */
    @Column(name = "method_signature", length = 200)
    private String methodSignature;

    /** IPv4 / IPv6 — pulled from X-Forwarded-For if behind gateway, else RemoteAddr. */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
