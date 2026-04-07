package com.sber.dlmm.oracle.repository;

import com.sber.dlmm.oracle.entity.PriceFeed;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PriceFeedRepository extends JpaRepository<PriceFeed, UUID> {

    Optional<PriceFeed> findByAssetSymbol(String assetSymbol);

    List<PriceFeed> findAll();
}
