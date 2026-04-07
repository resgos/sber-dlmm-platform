package com.sber.dlmm.pool.repository;

import com.sber.dlmm.common.enums.PoolStatus;
import com.sber.dlmm.pool.entity.LiquidityPool;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LiquidityPoolRepository extends JpaRepository<LiquidityPool, UUID> {

    Optional<LiquidityPool> findByTokenXIdAndTokenYIdAndBinStep(UUID tokenXId, UUID tokenYId, int binStep);

    List<LiquidityPool> findByStatus(PoolStatus status);

    List<LiquidityPool> findByTokenXIdOrTokenYId(UUID tokenId1, UUID tokenId2);

    Page<LiquidityPool> findAll(Pageable pageable);

    @Query("SELECT p FROM LiquidityPool p WHERE p.status = 'ACTIVE' " +
            "ORDER BY (p.totalTvlX + p.totalTvlY) DESC")
    Page<LiquidityPool> findTopByTvl(Pageable pageable);
}
