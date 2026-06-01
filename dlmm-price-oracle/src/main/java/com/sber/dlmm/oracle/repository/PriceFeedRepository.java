package com.sber.dlmm.oracle.repository;

import com.sber.dlmm.oracle.entity.PriceFeed;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link PriceFeed} — the latest-known
 * spot/TWAP price row per asset.
 *
 * <p>Inherits the standard CRUD operations from {@link JpaRepository} and
 * adds symbol-keyed lookup. There is one feed row per asset symbol, so
 * lookups by symbol resolve to a single feed.
 */
public interface PriceFeedRepository extends JpaRepository<PriceFeed, UUID> {

    /**
     * Finds the price feed for a given asset ticker.
     *
     * @param assetSymbol asset ticker to look up (e.g. {@code "USD"}, {@code "SBTC"})
     * @return the matching feed, or {@link Optional#empty()} if no feed exists for that symbol
     */
    Optional<PriceFeed> findByAssetSymbol(String assetSymbol);

    /**
     * Returns every price feed (one per tracked asset), in no guaranteed order.
     *
     * @return all price feed rows; empty if none exist
     */
    List<PriceFeed> findAll();
}
