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

@Repository
public interface AutoClaimLogRepository extends JpaRepository<AutoClaimLog, UUID> {

    /** History for the /history endpoint — newest first. */
    Page<AutoClaimLog> findByUserIdOrderByFiredAtDesc(UUID userId, Pageable pageable);

    /**
     * Most recent SUCCESS row for a given position — used by the
     * scheduler to enforce the per-position 1h cooldown.
     */
    @Query("SELECT l FROM AutoClaimLog l " +
           "WHERE l.positionId = :positionId AND l.status = com.sber.dlmm.fee.entity.AutoClaimLog$Status.SUCCESS " +
           "ORDER BY l.firedAt DESC LIMIT 1")
    Optional<AutoClaimLog> findLatestSuccessForPosition(@Param("positionId") UUID positionId);

    /**
     * Count of successful fires for a user since the given cutoff —
     * used to enforce the rolling-24h dailyCap.
     */
    @Query("SELECT COUNT(l) FROM AutoClaimLog l " +
           "WHERE l.userId = :userId " +
           "AND l.status = com.sber.dlmm.fee.entity.AutoClaimLog$Status.SUCCESS " +
           "AND l.firedAt >= :cutoff")
    long countSuccessByUserSince(@Param("userId") UUID userId,
                                  @Param("cutoff") LocalDateTime cutoff);
}
