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

    public enum Action { SET, LIFT_REQUESTED, LIFTED }

    public enum Scope { ALL_NEW_POSITIONS }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Action action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Scope scope;

    @Column(length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "effective_at", nullable = false)
    private LocalDateTime effectiveAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (effectiveAt == null) effectiveAt = createdAt;
        if (scope == null) scope = Scope.ALL_NEW_POSITIONS;
    }
}
