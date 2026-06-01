package com.sber.dlmm.token.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A single user's holding of a single token (table {@code user_balances}).
 *
 * <p>Primary key is the composite {@code (userId, tokenId)} via
 * {@link UserBalanceId} ({@code @IdClass}) — at most one row per user per
 * token. The balance is split into two buckets whose sum is the user's
 * total holding:
 * <ul>
 *   <li>{@link #available} — freely spendable;</li>
 *   <li>{@link #locked} — reserved (e.g. committed to an open order /
 *       in-flight operation) and not spendable until released.</li>
 * </ul>
 * Transitions between the buckets are performed atomically by the
 * conditional {@code @Modifying} updates in {@code UserBalanceRepository}
 * (lock/unlock/deduct/credit), which guard against overdraft in the WHERE
 * clause rather than read-modify-write in Java.
 *
 * <h2>Money / scale</h2>
 * <p>Both {@link #available} and {@link #locked} are raw integer amounts at
 * the uniform ×10⁴ platform scale (#14): 1 unit = 10⁻⁴ token. The backend
 * never applies the token's {@code decimals}; the user-ui converts raw↔human
 * at the API boundary.
 *
 * <p>Lombok entity (generated accessors). No {@code @Version} column: writes
 * go through atomic conditional UPDATEs, so optimistic locking is not used.
 */
@Entity
@Table(name = "user_balances")
@IdClass(UserBalanceId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserBalance {

    /** Owning user's id; first half of the composite key. */
    @Id
    @Column(nullable = false)
    private UUID userId;

    /** Held token's catalog id; second half of the composite key. */
    @Id
    @Column(nullable = false)
    private UUID tokenId;

    /** Freely spendable balance (raw ×10⁴ units). */
    @Column(nullable = false)
    private long available;

    /** Reserved / non-spendable balance (raw ×10⁴ units). */
    @Column(nullable = false)
    private long locked;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Last time the custody-fee scheduled job touched this row.
     * NULL for rows that have never been accrued — the job treats
     * those as "fresh" and uses updated_at as the start of the
     * accrual window. See CustodyFeeJob + DB-MIGRATION-CONVENTION.md.
     */
    @Column(name = "last_custody_fee_at")
    private LocalDateTime lastCustodyFeeAt;

    /**
     * JPA lifecycle callback fired before every INSERT and UPDATE: refreshes
     * {@link #updatedAt} to the current time so the row always carries its
     * last-mutation timestamp. Note the atomic {@code @Modifying} repository
     * updates set {@code updatedAt} in SQL directly and bypass this callback.
     */
    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
