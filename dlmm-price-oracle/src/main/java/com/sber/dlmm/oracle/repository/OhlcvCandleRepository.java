package com.sber.dlmm.oracle.repository;

import com.sber.dlmm.oracle.entity.OhlcvCandle;
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
 * Sprint 9-DS-r4 (P1-11) — OHLCV repository.
 */
@Repository
public interface OhlcvCandleRepository extends JpaRepository<OhlcvCandle, UUID> {

    /**
     * Used by the aggregator to merge a re-delivered Kafka event into
     * an existing bucket instead of throwing on the unique constraint.
     */
    Optional<OhlcvCandle> findByPoolIdAndIntervalSecAndOpenTime(UUID poolId, int intervalSec, LocalDateTime openTime);

    /**
     * Chart endpoint backend. Returns the most recent {@code limit}
     * candles for a (pool, interval), newest first. The controller
     * reverses to chronological order before serialising — TradingView
     * expects oldest-first.
     */
    @Query("SELECT c FROM OhlcvCandle c WHERE c.poolId = :poolId AND c.intervalSec = :intervalSec " +
           "ORDER BY c.openTime DESC")
    List<OhlcvCandle> findRecent(@Param("poolId") UUID poolId,
                                  @Param("intervalSec") int intervalSec,
                                  Pageable pageable);
}
