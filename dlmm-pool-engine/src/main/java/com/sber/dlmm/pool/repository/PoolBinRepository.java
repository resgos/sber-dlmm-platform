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

    /**
     * Sprint 9-DS-r4 (P1-12) — TVL reconciliation input. Returns the
     * true sum of reserves across every bin in the pool. The rollups
     * {@code LiquidityPool.totalTvlX/Y} are incrementally maintained
     * by swap + add/remove paths and can drift if any of those paths
     * has a bug; the reconciliation job compares this aggregate
     * against the cached rollup and alerts on mismatch.
     *
     * <p>Returns a 2-long array: [sumReserveX, sumReserveY]. Single
     * query per pool keeps the nightly reconciliation cheap (one
     * scan per pool of the pool_bins partition).
     */
    @Query("SELECT COALESCE(SUM(b.reserveX), 0L), COALESCE(SUM(b.reserveY), 0L) " +
            "FROM PoolBin b WHERE b.poolId = :poolId")
    Object[] sumReservesByPool(@Param("poolId") UUID poolId);
}
