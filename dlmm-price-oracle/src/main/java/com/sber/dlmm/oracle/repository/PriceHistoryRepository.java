package com.sber.dlmm.oracle.repository;

import com.sber.dlmm.oracle.entity.PriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PriceHistoryRepository extends JpaRepository<PriceHistory, UUID> {

    List<PriceHistory> findByPriceFeedIdAndTimestampEpochMsGreaterThanEqual(UUID priceFeedId, long fromTimestamp);

    List<PriceHistory> findByPriceFeedIdOrderByTimestampEpochMsDesc(UUID priceFeedId);
}
