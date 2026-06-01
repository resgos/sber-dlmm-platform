package com.sber.dlmm.user.entity;

import com.sber.dlmm.common.enums.KycStatus;
import com.sber.dlmm.common.enums.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Core user account — the identity and authentication record owned by
 * user-service (table {@code users}).
 *
 * <p>This is the source of truth for who a user is and what they may do.
 * user-service issues the JWT from this row, embedding {@link #role} and
 * {@link #kycStatus} as claims; the gateway then forwards them downstream as
 * {@code X-User-Role} / {@code X-Kyc-Status} headers. KYC-gated operations
 * (swaps, liquidity, withdrawals) require {@code kycStatus == VERIFIED}.
 *
 * <p>Identity invariants: {@link #sberId} and {@link #email} are unique
 * business keys used to resolve a user at login/registration (the DB enforces
 * uniqueness on {@code sberId}; {@code email}/{@code phone} uniqueness is
 * guarded in the service layer via existence checks). The surrogate
 * {@link #id} (UUID) is the stable foreign-key target referenced across
 * services (positions, transactions, org membership, 2FA, self-restrictions).
 *
 * <p><strong>Sensitive field:</strong> {@link #passwordHash} holds the BCrypt
 * hash of the user's password — never the plaintext, and never exposed in any
 * response DTO ({@link com.sber.dlmm.user.dto.UserProfileResponse} omits it).
 *
 * <p>Lifecycle timestamps {@link #createdAt}/{@link #updatedAt} are managed
 * automatically by the JPA callbacks {@link #onCreate()} / {@link #onUpdate()};
 * application code never sets them directly.
 *
 * <p>Lombok generates the all-args / no-args constructors, the builder, and
 * all getters/setters.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    /** Surrogate primary key; server-generated UUID. Stable FK target used
     *  by every other service to reference this user. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Sber ecosystem identifier — unique business key, supplied at
     *  registration and used as a login/lookup handle. */
    @Column(unique = true, nullable = false)
    private String sberId;

    /** Contact email; also a login identifier. Uniqueness is enforced in the
     *  service layer (existence check), not by a DB constraint here. */
    @Column(nullable = false)
    private String email;

    /** Contact phone in {@code +7XXXXXXXXXX} format (validated at the DTO). */
    @Column(nullable = false)
    private String phone;

    /** Given name. */
    @Column(nullable = false)
    private String firstName;

    /** Family name. */
    @Column(nullable = false)
    private String lastName;

    /** KYC verification state; persisted as its enum name. Defaults to
     *  {@link KycStatus#PENDING} on creation and is mirrored into the JWT
     *  {@code kycStatus} claim. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private KycStatus kycStatus = KycStatus.PENDING;

    /** Authorization role; persisted as its enum name. Defaults to
     *  {@link UserRole#USER} and is mirrored into the JWT {@code role} claim. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private UserRole role = UserRole.USER;

    /** BCrypt hash of the user's password. Sensitive — never logged, never
     *  returned in a response, never the plaintext. */
    @Column(nullable = false)
    private String passwordHash;

    /** Row creation timestamp; set once by {@link #onCreate()} and never
     *  updated thereafter ({@code updatable = false}). */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Last-modification timestamp; refreshed by {@link #onUpdate()} on every
     *  update. */
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Batch #6 — populated by UserService.login() on every successful
     * authentication. NULL для users который never logged in (seed has
     * это backfilled by docker/07-seed-fix-backend-bugs.sql).
     * Source-of-truth для /admin/pilots/health engagement score (B-06).
     */
    @Column
    private LocalDateTime lastLoginAt;

    /**
     * JPA lifecycle hook fired before the row is first inserted. Stamps both
     * {@link #createdAt} and {@link #updatedAt} with the current time so they
     * start out equal.
     */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * JPA lifecycle hook fired before every update. Refreshes
     * {@link #updatedAt} to the current time; {@link #createdAt} is left
     * untouched.
     */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
