package com.sber.dlmm.pool.repository;

import com.sber.dlmm.pool.entity.PoolBin;
import com.sber.dlmm.pool.entity.PoolBinId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PoolBinRepository extends JpaRepository<PoolBin, PoolBinId> {

    List<PoolBin> findByPoolIdAndBinIdBetween(UUID poolId, int minBin, int maxBin);

    List<PoolBin> findByPoolIdAndLiquidityGreaterThan(UUID poolId, long minLiquidity);

    Optional<PoolBin> findByPoolIdAndBinId(UUID poolId, int binId);

    @Query("SELECT b FROM PoolBin b WHERE b.poolId = :poolId " +
            "AND b.liquidity > 0 ORDER BY b.binId ASC")
    List<PoolBin> findActiveBins(@Param("poolId") UUID poolId);
}
