package com.sber.dlmm.token.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Sprint 5 #5.6 — corporate issuer who wants to list a token on DLMM.
 *
 * <p>Lifecycle: PENDING (just registered) → APPROVED | REJECTED (admin
 * KYB review). Only APPROVED issuers can create tokens via
 * {@code POST /api/v1/b2b/issuers/{id}/tokens} (Sprint 6+).
 *
 * <p>Tier (BASIC/PRO/ENTERPRISE) drives billing engine pricing (#5.7).
 */
@Entity
@Table(name = "b2b_issuers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class B2BIssuer {

    /**
     * KYB (Know-Your-Business) review state.
     * {@code PENDING} just registered → {@code APPROVED} (may create tokens)
     * or {@code REJECTED} (see {@link #rejectionReason}).
     */
    public enum KybStatus { PENDING, APPROVED, REJECTED }

    /**
     * Commercial tier driving the billing engine's pricing (#5.7):
     * {@code BASIC} / {@code PRO} / {@code ENTERPRISE}.
     */
    public enum Tier { BASIC, PRO, ENTERPRISE }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Russian taxpayer number (ИНН); the natural business key — DB-unique
     *  so one legal entity maps to at most one issuer. */
    @Column(nullable = false, unique = true, length = 12)
    private String inn;

    @Column(name = "legal_name", nullable = false, length = 255)
    private String legalName;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "contact_email", nullable = false, length = 255)
    private String contactEmail;

    @Column(name = "contact_phone", length = 32)
    private String contactPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Tier tier;

    @Enumerated(EnumType.STRING)
    @Column(name = "kyb_status", nullable = false, length = 20)
    private KybStatus kybStatus;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * JPA lifecycle callback fired before INSERT: stamps {@link #createdAt}
     * and applies safe defaults for a freshly-registered issuer —
     * {@link KybStatus#PENDING} (awaiting review) and {@link Tier#BASIC} —
     * when the caller left them unset.
     */
    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (kybStatus == null) kybStatus = KybStatus.PENDING;
        if (tier == null) tier = Tier.BASIC;
    }
}
