package com.sber.dlmm.fee.entity;

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
 * A single fee-accrual ledger row: one token amount earned by one LP position,
 * in one token, at one point in time. Each swap that touches a position's bins
 * produces accrual rows here; the {@code fee_accruals} table is therefore the
 * source of truth for "how much has this position earned, and how much is still
 * unclaimed".
 *
 * <p><b>Accrual / claim lifecycle.</b> A row is created {@link #claimed} =
 * {@code false} (unclaimed, earning sitting on the position). When the user (or
 * the auto-claim scheduler) claims, the matching rows are flipped to
 * {@code claimed = true} and stamped with {@link #claimedAt} and the settling
 * {@link #txId}. A claimed row is never reverted, so summing {@code amount} over
 * all rows gives total-earned, and summing only the unclaimed rows gives the
 * still-owed balance.
 *
 * <p><b>Amount scale.</b> {@link #amount} is a raw integer in platform base
 * units where 1 unit = 10⁻⁴ token (the uniform ×10⁴ scale, see {@code #14} in
 * the repo CLAUDE.md); it is never divided by the token's own {@code decimals}.
 * The UI converts raw↔human at the API boundary.
 *
 * <p>No optimistic-locking {@code @Version} is present: accrual rows are
 * insert-then-flip-once and never concurrently mutated.
 */
@Entity
@Table(name = "fee_accruals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FeeAccrual {

    /** Surrogate primary key; DB-generated UUID, immutable once assigned. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** LP position that earned this fee. */
    @Column(name = "position_id", nullable = false)
    private UUID positionId;

    /** Pool the position belongs to. */
    @Column(name = "pool_id", nullable = false)
    private UUID poolId;

    /** Owner of the position (the LP who can claim this accrual). */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Token this fee is denominated in (a pool's X or Y leg). */
    @Column(name = "token_id", nullable = false)
    private UUID tokenId;

    /** Fee earned, as a raw base-unit integer (×10⁴ scale; 1 = 10⁻⁴ token). */
    @Column(name = "amount", nullable = false)
    private long amount;

    /** Settlement transaction that paid this accrual out; {@code null} until claimed. */
    @Column(name = "tx_id")
    private UUID txId;

    /** {@code true} once the accrual has been claimed (credited to the user). */
    @Builder.Default
    @Column(name = "claimed", nullable = false)
    private boolean claimed = false;

    /** When the fee was accrued; defaulted to now on persist if unset. */
    @Column(name = "accrued_at", nullable = false)
    private LocalDateTime accruedAt;

    /** When the accrual was claimed; {@code null} while still unclaimed. */
    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    /**
     * JPA lifecycle hook: stamps {@link #accruedAt} with the current time when
     * the row is first persisted and no accrual timestamp was supplied.
     */
    @PrePersist
    public void prePersist() {
        if (accruedAt == null) {
            accruedAt = LocalDateTime.now();
        }
    }
}
