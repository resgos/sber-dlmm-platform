package com.sber.dlmm.transaction.repository;

import com.sber.dlmm.transaction.entity.AmlAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AmlAlertRepository extends JpaRepository<AmlAlert, UUID> {

    /**
     * Sprint 6 #6.9 — dedup guard: don't re-fire the same pattern for
     * the same user within the recent window. Scanner walks 15-min ticks
     * so without this every consecutive tick on a 1h-window pattern
     * would re-emit the same alert.
     */
    Optional<AmlAlert> findFirstByUserIdAndPatternAndDetectedAtAfterOrderByDetectedAtDesc(
            UUID userId, AmlAlert.Pattern pattern, LocalDateTime since);
}
