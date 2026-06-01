package com.sber.dlmm.oracle.repository;

import com.sber.dlmm.oracle.entity.PriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link PriceHistory} — the append-only
 * time series of price samples behind each asset's feed.
 *
 * <p>Inherits standard CRUD from {@link JpaRepository} and adds queries
 * scoped to a single feed, used to assemble trailing windows (e.g. for
 * TWAP and 24h-change computations).
 */
public interface PriceHistoryRepository extends JpaRepository<PriceHistory, UUID> {

    /**
     * Returns all samples for one feed at or after a given instant — i.e. the
     * trailing window {@code [fromTimestamp, now]} used for windowed price math.
     *
     * <p>Results are in no guaranteed order; callers that need ordering must sort.
     *
     * @param priceFeedId   owning {@link PriceFeed#getId() feed id} to filter by
     * @param fromTimestamp inclusive lower bound on {@code timestampEpochMs}, as Unix epoch milliseconds
     * @return matching samples within the window; empty if none
     */
    List<PriceHistory> findByPriceFeedIdAndTimestampEpochMsGreaterThanEqual(UUID priceFeedId, long fromTimestamp);

    /**
     * Returns the full sample history for one feed, newest first.
     *
     * @param priceFeedId owning {@link PriceFeed#getId() feed id} to filter by
     * @return all samples for the feed, ordered by {@code timestampEpochMs} descending (most recent first); empty if none
     */
    List<PriceHistory> findByPriceFeedIdOrderByTimestampEpochMsDesc(UUID priceFeedId);
}
