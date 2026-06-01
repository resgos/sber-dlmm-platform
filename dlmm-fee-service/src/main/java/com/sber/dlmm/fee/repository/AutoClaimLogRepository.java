package com.sber.dlmm.fee.repository;

import com.sber.dlmm.fee.entity.AutoClaimLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository over {@link AutoClaimLog} audit rows. Backs both
 * the auto-claim {@code /history} read endpoint and the scheduler's safety
 * guards: the per-position 1h cooldown and the rolling-24h dailyCap. The two
 * guard queries deliberately count only {@link AutoClaimLog.Status#SUCCESS}
 * rows, since FAILURE/SKIPPED attempts moved no value.
 */
@Repository
public interface AutoClaimLogRepository extends JpaRepository<AutoClaimLog, UUID> {

    /**
     * History for the /history endpoint — newest first.
     *
     * @param userId   user whose auto-claim history to page
     * @param pageable page index and size (sort is fixed to {@code firedAt} descending)
     * @return a page of the user's auto-claim log rows, most recent first
     */
    Page<AutoClaimLog> findByUserIdOrderByFiredAtDesc(UUID userId, Pageable pageable);

    /**
     * Most recent SUCCESS row for a given position — used by the
     * scheduler to enforce the per-position 1h cooldown.
     *
     * @param positionId the position to inspect
     * @return the latest successful auto-claim for that position, or empty if it
     *         has never successfully auto-claimed
     */
    @Query("SELECT l FROM AutoClaimLog l " +
           "WHERE l.positionId = :positionId AND l.status = com.sber.dlmm.fee.entity.AutoClaimLog$Status.SUCCESS " +
           "ORDER BY l.firedAt DESC LIMIT 1")
    Optional<AutoClaimLog> findLatestSuccessForPosition(@Param("positionId") UUID positionId);

    /**
     * Count of successful fires for a user since the given cutoff —
     * used to enforce the rolling-24h dailyCap.
     *
     * @param userId user whose successful auto-claims to count
     * @param cutoff lower time bound (inclusive); typically now − 24h
     * @return number of SUCCESS auto-claims for the user at or after {@code cutoff}
     */
    @Query("SELECT COUNT(l) FROM AutoClaimLog l " +
           "WHERE l.userId = :userId " +
           "AND l.status = com.sber.dlmm.fee.entity.AutoClaimLog$Status.SUCCESS " +
           "AND l.firedAt >= :cutoff")
    long countSuccessByUserSince(@Param("userId") UUID userId,
                                  @Param("cutoff") LocalDateTime cutoff);
}
