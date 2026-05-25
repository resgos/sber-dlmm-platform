package com.sber.dlmm.user.entity;

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
 * Sprint 11 G-21 — Member of an {@link Org}.
 *
 * <p>A row is created in one of two states:
 * <ul>
 *   <li>{@code ACTIVE} on org creation (the OWNER) — {@code userId}
 *       always populated.</li>
 *   <li>{@code PENDING} on invite — {@code email} populated;
 *       {@code userId} is {@code null} until accept (the invited user
 *       might not have signed up yet). The accept flow resolves
 *       {@code email} → {@code userId} and flips status to
 *       {@code ACTIVE}.</li>
 * </ul>
 *
 * <p>Role taxonomy mirrors the frontend store
 * (see {@code dlmm-user-ui/src/store/teamStore.ts}):
 * OWNER &gt; FINANCE_MGR &gt; ACCOUNTANT &gt; AUDITOR &gt; VIEWER.
 * Gateway middleware (Sprint 12) reads this enum out of the JWT
 * {@code orgRole} claim and gates endpoints.
 */
@Entity
@Table(name = "org_members")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrgMember {

    public enum Role { OWNER, FINANCE_MGR, ACCOUNTANT, AUDITOR, VIEWER }

    public enum Status { ACTIVE, PENDING }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    /** {@code null} for PENDING invites — populated on accept. */
    @Column(name = "user_id")
    private UUID userId;

    /** Invitee's email. Always populated (used to resolve
     *  {@link #userId} on accept; also displayed in the UI for
     *  PENDING rows). */
    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

    @PrePersist
    void onCreate() {
        if (joinedAt == null) joinedAt = LocalDateTime.now();
    }
}
