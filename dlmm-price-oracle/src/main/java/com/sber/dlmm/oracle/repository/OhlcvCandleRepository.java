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
 *
 * <p>Spring Data JPA repository for {@link OhlcvCandle} (per-pool OHLCV
 * candles). Backs both the aggregator's upsert path (look up an existing
 * bucket by its natural key) and the chart endpoint's "recent candles"
 * read. Inherits standard CRUD from {@link JpaRepository}.
 */
@Repository
public interface OhlcvCandleRepository extends JpaRepository<OhlcvCandle, UUID> {

    /**
     * Looks up a candle by its natural (pool, interval, open-time) key.
     *
     * <p>Used by the aggregator to merge a re-delivered Kafka event into
     * an existing bucket instead of throwing on the unique constraint.
     *
     * @param poolId      pool the candle belongs to
     * @param intervalSec bucket width in seconds (e.g. {@code 60} for 1-minute candles)
     * @param openTime    UTC start of the bucket
     * @return the matching candle, or {@link Optional#empty()} if that bucket has no row yet
     */
    Optional<OhlcvCandle> findByPoolIdAndIntervalSecAndOpenTime(UUID poolId, int intervalSec, LocalDateTime openTime);

    /**
     * Chart endpoint backend. Returns the most recent {@code limit}
     * candles for a (pool, interval), newest first. The controller
     * reverses to chronological order before serialising — TradingView
     * expects oldest-first.
     *
     * @param poolId      pool to fetch candles for
     * @param intervalSec bucket width in seconds (e.g. {@code 60} for 1-minute candles)
     * @param pageable    page request capping the result count (the "limit"); its sort is ignored — ordering is fixed to {@code openTime} descending by the query
     * @return up to {@code pageable.pageSize} candles ordered by {@code openTime} descending (newest first); empty if none
     */
    @Query("SELECT c FROM OhlcvCandle c WHERE c.poolId = :poolId AND c.intervalSec = :intervalSec " +
           "ORDER BY c.openTime DESC")
    List<OhlcvCandle> findRecent(@Param("poolId") UUID poolId,
                                  @Param("intervalSec") int intervalSec,
                                  Pageable pageable);
}
