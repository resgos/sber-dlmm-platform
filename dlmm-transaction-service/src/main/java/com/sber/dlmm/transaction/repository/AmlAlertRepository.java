package com.sber.dlmm.transaction.repository;

import com.sber.dlmm.transaction.entity.AmlAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link AmlAlert} records.
 *
 * <p>Beyond the inherited CRUD, it provides the scanner's dedup guard
 * that detects whether an equivalent alert was already raised for the
 * same user and pattern inside the recent window.
 */
@Repository
public interface AmlAlertRepository extends JpaRepository<AmlAlert, UUID> {

    /**
     * Sprint 6 #6.9 — dedup guard: don't re-fire the same pattern for
     * the same user within the recent window. Scanner walks 15-min ticks
     * so without this every consecutive tick on a 1h-window pattern
     * would re-emit the same alert. Returns the most recent match so the
     * caller can compare its {@code detectedAt} against the new tick.
     *
     * @param userId  user the candidate alert is about
     * @param pattern detection pattern to match
     * @param since   lower bound — only consider alerts detected strictly after this instant
     * @return the latest matching alert in the window, or empty if none (so it is safe to fire)
     */
    Optional<AmlAlert> findFirstByUserIdAndPatternAndDetectedAtAfterOrderByDetectedAtDesc(
            UUID userId, AmlAlert.Pattern pattern, LocalDateTime since);
}
