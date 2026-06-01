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
 * Sprint 11 G-21 — Organisation (Dmitry medium-business blocker).
 *
 * <p>An org is the umbrella that owns members + role assignments. Today
 * it is functionally a single-tenant container — billing, audit log
 * attribution, and the gateway's permission middleware ride on
 * {@code orgId}. We deliberately do not yet model org-owned
 * positions/balances — those still attach to {@code userId} as before;
 * the org sits next to them. Re-attribution (Sprint 12+) reads the
 * member's {@code orgId} from the JWT and writes it as a denormalised
 * column on transactions / positions, but that is out of scope here.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@code POST /api/v1/orgs} — creator becomes OWNER (single
 *       OWNER initially; co-OWNER promotion via
 *       {@code PUT .../members/{id}/role}). The owner's {@code User}
 *       row remains untouched.</li>
 *   <li>{@code GET /api/v1/orgs/me} — returns the org the caller is
 *       a member of (ACTIVE state); 404 if none. A user belongs to at
 *       most one org by design — multi-org membership is on the
 *       Sprint 12 backlog.</li>
 * </ul>
 */
@Entity
@Table(name = "orgs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Org {

    /** Surrogate primary key; server-generated UUID. Referenced by
     *  {@link OrgMember#getOrgId()} and embedded in the JWT {@code orgId} claim. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Human-readable organisation name (max 200 chars). */
    @Column(nullable = false, length = 200)
    private String name;

    /** FK → users.id of the creator. Kept even after OWNER role
     *  transfer (audit provenance); current owners are derived from
     *  {@link OrgMember} rows with role=OWNER. */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /** Creation timestamp; set once and never updated
     *  ({@code updatable = false}). Defaulted in {@link #onCreate()}. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * JPA lifecycle hook fired before insert. Defaults {@link #createdAt} to
     * the current time when the caller did not set it.
     */
    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
