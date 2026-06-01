package com.sber.dlmm.token.repository;

import com.sber.dlmm.token.entity.SpasiboWritebackEntry;
import com.sber.dlmm.token.entity.SpasiboWritebackEntry.Status;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sprint 9-DS-r4 (P1-17) — SberSpasibo write-back queue.
 *
 * <p>Three query shapes:
 *   - {@link #findByDlmmTxId} — idempotency check before insert
 *   - {@link #findShippable} — scheduler input (PENDING or RETRY)
 *   - {@link #sumPointsByUserSince} — cap enforcement (design §3)
 */
@Repository
public interface SpasiboWritebackRepository extends JpaRepository<SpasiboWritebackEntry, UUID> {

    /**
     * Idempotency check before insert: has a write-back entry already been
     * created for this originating DLMM transaction? Backs the
     * {@code uk_spasibo_wb_dlmm_tx} unique constraint at the application
     * layer so a re-delivered Kafka event is treated as a duplicate.
     *
     * @param dlmmTxId the originating DLMM transaction id
     * @return the existing entry for that transaction, or empty if first-seen
     */
    Optional<SpasiboWritebackEntry> findByDlmmTxId(UUID dlmmTxId);

    /**
     * Scheduler input: pick up entries that need shipping to Spasibo BU.
     * RETRY entries are returned alongside PENDING because the backoff
     * is enforced by the scheduler itself (re-checking attempt_count
     * + last_attempt_at), keeping this query a simple status-equality.
     *
     * <p>Results are ordered oldest-created first ({@code createdAt} ascending)
     * so the queue drains FIFO; {@code pageable} bounds the batch size.
     *
     * @param statuses the statuses to ship (typically PENDING + RETRY)
     * @param pageable batch size / ordering bound for one scheduler tick
     * @return matching entries, oldest first (possibly empty)
     */
    @Query("SELECT e FROM SpasiboWritebackEntry e WHERE e.status IN (:statuses) " +
           "ORDER BY e.createdAt ASC")
    List<SpasiboWritebackEntry> findShippable(@Param("statuses") List<Status> statuses,
                                               Pageable pageable);

    /**
     * Cap enforcement input: sum of accepted+pending points for a user
     * within the rolling window. Compared against
     * {@code dlmm.spasibo.writeback.cap-daily-points} /
     * {@code -cap-monthly-points} before each new accrual to refuse
     * cap-overflow accrual at insert time.
     *
     * <p>Includes PENDING + RETRY so a flood of in-flight accruals can't
     * race the cap; only REJECTED/DEAD_LETTER are excluded.
     */
    /**
     * HOTFIX 2026-05-26 — Hibernate 6 (Spring Boot 3.2.5) rejects the
     * inline fully-qualified-enum-literal form of IN-clauses at query-
     * validation time during startup ("Validation failed for query for
     * method sumPointsByUserSince"). Use parameter binding via a default
     * method to preserve the public API while routing through a query
     * Hibernate accepts.
     *
     * @param userId   the user whose accrued points are summed
     * @param since    rolling-window lower bound (inclusive) on {@code createdAt}
     * @param statuses the statuses to include in the sum
     * @return total {@code amountPoints} for that user/window/statuses, or 0 if none
     */
    @Query("SELECT COALESCE(SUM(e.amountPoints), 0) FROM SpasiboWritebackEntry e " +
           "WHERE e.userId = :userId AND e.createdAt >= :since " +
           "AND e.status IN :statuses")
    long sumPointsByUserSinceInternal(@Param("userId") UUID userId,
                                      @Param("since") LocalDateTime since,
                                      @Param("statuses") List<Status> statuses);

    /**
     * Public API kept stable — implementation hard-codes the cap-counting
     * statuses (PENDING + RETRY + ACCEPTED, excludes REJECTED/DEAD_LETTER)
     * matching the original inline query.
     *
     * @param userId the user whose accrued points are summed
     * @param since  rolling-window lower bound (inclusive) on {@code createdAt}
     * @return total points counted toward the user's daily/monthly cap
     */
    default long sumPointsByUserSince(UUID userId, LocalDateTime since) {
        return sumPointsByUserSinceInternal(userId, since,
                List.of(Status.PENDING, Status.RETRY, Status.ACCEPTED));
    }
}
