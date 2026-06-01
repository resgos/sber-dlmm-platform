package com.sber.dlmm.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 11 G-20 — per-user 2FA TOTP state.
 *
 * <p>One row per user that has ever enabled or partially-enabled 2FA. The
 * row's existence does NOT imply 2FA is on — {@code enabled} is the gate.
 * The whole row is wiped on {@code disable()}; this keeps the model simple
 * (no "soft-disable" half-state) and matches user mental model (off means
 * off — secret rotates on re-enable).
 *
 * <p>{@code userId} doubles as the primary key. Each user has at most one
 * row, so a separate generated UUID would just be ceremony.
 *
 * <p>Recovery codes are stored already-bcrypt-hashed. We never see the
 * plaintext after begin-setup returns it once to the client; the user is
 * told to write them down. The {@code recoveryCodesUsed} counter is
 * informational only — the source of truth for "is code X still valid?"
 * is whether its hash is still in the {@code recoveryCodesHashed} array
 * (consumed codes are removed in-place).
 */
@Entity
@Table(name = "user_two_factor")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserTwoFactor {

    /** FK → {@code users.id}; doubles as the primary key (one 2FA row per
     *  user, see class doc). Never reassigned ({@code updatable = false}). */
    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /**
     * Base32-encoded TOTP secret (160-bit / 32 chars). Stored plaintext;
     * compromising the DB is already game-over (passwords too). HSM-backed
     * storage would harden this — out of scope for the MVP.
     */
    @Column(nullable = false, length = 64)
    private String secret;

    /** The gate: {@code true} once setup is confirmed and 2FA is enforced at
     *  login. A row can exist with {@code enabled == false} during begin-setup
     *  before the user verifies their first code. Defaults to {@code false}. */
    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = false;

    /** Timestamp 2FA was switched on; {@code null} while still in setup. */
    @Column(name = "enabled_at")
    private LocalDateTime enabledAt;

    /**
     * One-time recovery codes, bcrypt-hashed. Plaintext shown once at
     * setup time, never persisted. Consumed entries are removed from
     * the array on use, which both invalidates the code and gives
     * {@code recoveryCodesHashed.length} as the canonical "remaining"
     * count.
     *
     * <p>Postgres {@code varchar[]} via Hibernate 6's {@link SqlTypes#ARRAY}.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "recovery_codes_hashed", columnDefinition = "varchar[]")
    private String[] recoveryCodesHashed;

    /**
     * Cumulative count of consumed recovery codes — informational only,
     * never decreases. Useful for analytics ("X% of 2FA users have
     * burned at least one recovery code") and for the UI to show
     * usage history without re-deriving from the hashed array length.
     */
    @Column(name = "recovery_codes_used", nullable = false)
    @Builder.Default
    private int recoveryCodesUsed = 0;
}
