package com.sber.dlmm.fee.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 12 G-16 — backend swap for the frontend-only auto-claim MVP.
 *
 * <p>One row per user. Row only exists after the first GET or PUT;
 * absence is treated as the (disabled) default. {@link #skipPoolIds}
 * is a comma-separated UUID list — simple to query and to migrate,
 * and the frontend already round-trips it as an array.
 *
 * <p>PK = userId rather than a synthetic UUID. There's exactly one
 * policy per user; the FK-to-users invariant is enforced at the
 * application layer (token-service / user-service own that table).
 */
@Entity
@Table(name = "auto_claim_policies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AutoClaimPolicy {

    /** Primary key: the owning user. Exactly one policy row per user. */
    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Master on/off switch; only {@code true} policies are walked by the scheduler. */
    @Builder.Default
    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    /**
     * Minimum unclaimed total (X+Y, in base units) before the scheduler
     * will claim. NUMERIC(38,0) to match the frontend's {@code number}
     * shape — base units may exceed long range for low-precision tokens.
     */
    @Builder.Default
    @Column(name = "threshold_amount", nullable = false)
    private BigDecimal thresholdAmount = BigDecimal.valueOf(1_000);

    /**
     * Max auto-claims per rolling 24h window. {@code 0} = unlimited;
     * the default of 20 matches the frontend MVP's safety cap.
     */
    @Builder.Default
    @Column(name = "daily_cap", nullable = false)
    private int dailyCap = 20;

    /**
     * Comma-separated UUIDs of pools the user has flagged exception.
     * Stored as a single TEXT column for migration simplicity (Postgres
     * text[] would be neater but ties the schema to Postgres). Empty
     * string and null both mean "no exceptions".
     */
    @Column(name = "skip_pool_ids", length = 4_096)
    private String skipPoolIds;

    /** Last time the policy was created or modified; maintained by {@link #touchTimestamp()}. */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * JPA lifecycle hook: refreshes {@link #updatedAt} to the current time on
     * every insert and update so the column always reflects the latest change.
     */
    @PrePersist
    @PreUpdate
    public void touchTimestamp() {
        updatedAt = LocalDateTime.now();
    }
}
