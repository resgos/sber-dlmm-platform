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
 * Sprint 6 #6.7 — самозапрет на финансовые продукты per 115-ФЗ
 * amendment 2024.
 *
 * <p>Immutable append-only history: SET → optional LIFT_REQUESTED →
 * LIFTED. Active restriction = latest SET with no subsequent LIFTED
 * row past effective_at.
 */
@Entity
@Table(name = "user_self_restrictions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSelfRestriction {

    /**
     * The kind of history event this row records.
     * <ul>
     *   <li>{@code SET} — user imposes the self-restriction.</li>
     *   <li>{@code LIFT_REQUESTED} — user asks to remove it (a cooling-off
     *       step; the lift is not yet effective).</li>
     *   <li>{@code LIFTED} — the restriction is removed once
     *       {@code effective_at} has passed.</li>
     * </ul>
     */
    public enum Action { SET, LIFT_REQUESTED, LIFTED }

    /** What the restriction covers. Currently only
     *  {@code ALL_NEW_POSITIONS} (blocks opening any new position). */
    public enum Scope { ALL_NEW_POSITIONS }

    /** Surrogate primary key; server-generated UUID. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** FK → {@code users.id} — the user this restriction history belongs to. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Event type for this append-only row (see {@link Action}). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Action action;

    /** Products covered (see {@link Scope}). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Scope scope;

    /** Optional free-text rationale supplied by the user (max 500 chars). */
    @Column(length = 500)
    private String reason;

    /** When this history row was created. Defaulted in {@link #onCreate()}. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** When the action takes effect. For a cooling-off lift this is in the
     *  future; the service treats a restriction as active until a {@code LIFTED}
     *  row's {@code effectiveAt} has passed. Defaults to {@link #createdAt}. */
    @Column(name = "effective_at", nullable = false)
    private LocalDateTime effectiveAt;

    /**
     * JPA lifecycle hook fired before insert. Fills in sensible defaults so
     * callers may omit them: {@link #createdAt} → now, {@link #effectiveAt} →
     * {@code createdAt} (immediate), {@link #scope} →
     * {@link Scope#ALL_NEW_POSITIONS}.
     */
    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (effectiveAt == null) effectiveAt = createdAt;
        if (scope == null) scope = Scope.ALL_NEW_POSITIONS;
    }
}
