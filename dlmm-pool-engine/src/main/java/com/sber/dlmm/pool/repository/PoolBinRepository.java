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

/**
 * Persistence for {@link PoolBin} — the per-pool price buckets keyed by
 * {@link PoolBinId} (pool_id, bin_id). Backs the swap engine (which walks bins
 * around the active bin), the order-book/depth view, and TVL reconciliation.
 */
@Repository
public interface PoolBinRepository extends JpaRepository<PoolBin, PoolBinId> {

    /**
     * Every bin of a pool, populated or empty, in no particular order.
     *
     * @param poolId pool whose bins to load
     * @return all bins for the pool (may be empty)
     */
    List<PoolBin> findByPoolId(UUID poolId);

    /**
     * Bins of a pool whose id falls in the inclusive {@code [minBin, maxBin]}
     * window — e.g. loading just the bins a position's range spans.
     *
     * @param poolId pool to query
     * @param minBin lower bin id, inclusive
     * @param maxBin upper bin id, inclusive
     * @return bins in the range (unordered, may be empty)
     */
    List<PoolBin> findByPoolIdAndBinIdBetween(UUID poolId, int minBin, int maxBin);

    /**
     * Bins of a pool with liquidity strictly greater than the threshold — pass
     * 0 to get only non-empty bins.
     *
     * @param poolId       pool to query
     * @param minLiquidity exclusive lower bound on {@link PoolBin#getLiquidity()}
     * @return matching bins (unordered, may be empty)
     */
    List<PoolBin> findByPoolIdAndLiquidityGreaterThan(UUID poolId, long minLiquidity);

    /**
     * The single bin at an exact (pool, bin id) — the swap loop's
     * current-/next-bin lookup.
     *
     * @param poolId pool to query
     * @param binId  absolute bin id
     * @return the bin, or empty if that bin has never been touched
     */
    Optional<PoolBin> findByPoolIdAndBinId(UUID poolId, int binId);

    /**
     * Non-empty bins of a pool ({@code liquidity > 0}) ordered by ascending bin
     * id, i.e. by ascending price. Drives the order-book/depth rendering where
     * traversal order matters.
     *
     * @param poolId pool to query
     * @return active bins, lowest bin id (price) first (may be empty)
     */
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
