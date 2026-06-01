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

/**
 * Persistence for the {@link LiquidityPool} aggregate root — the DLMM trading
 * pools. CRUD comes from {@link JpaRepository}; the methods below add the
 * domain lookups the pool/swap services need.
 */
@Repository
public interface LiquidityPoolRepository extends JpaRepository<LiquidityPool, UUID> {

    /**
     * Resolves the single pool for an exact (token_x, token_y, bin_step) triple
     * — the pool's unique business key. Used to detect duplicates on create and
     * to route a swap/liquidity request to its pool.
     *
     * @param tokenXId base-asset token id
     * @param tokenYId quote-asset token id
     * @param binStep  bin step in basis points
     * @return the matching pool, or empty if none exists for that triple
     */
    Optional<LiquidityPool> findByTokenXIdAndTokenYIdAndBinStep(UUID tokenXId, UUID tokenYId, int binStep);

    /**
     * All pools in a given lifecycle state (e.g. every {@code ACTIVE} pool for
     * scheduled liquidity/volume jobs).
     *
     * @param status lifecycle state to filter on
     * @return matching pools (unordered, may be empty)
     */
    List<LiquidityPool> findByStatus(PoolStatus status);

    /**
     * Every pool that references the given token on either side of the pair.
     * Both arguments are normally the same token id — the OR spans the X and Y
     * columns. Used to list the pools a token participates in.
     *
     * @param tokenId1 token id to match against token_x_id
     * @param tokenId2 token id to match against token_y_id (usually equal to tokenId1)
     * @return pools holding that token as X or Y (unordered, may be empty)
     */
    List<LiquidityPool> findByTokenXIdOrTokenYId(UUID tokenId1, UUID tokenId2);

    /**
     * Pageable listing of all pools for the pools grid.
     *
     * @param pageable page/size/sort
     * @return one page of pools
     */
    Page<LiquidityPool> findAll(Pageable pageable);

    /**
     * "Top pools" leaderboard: ACTIVE pools only, ordered by combined nominal
     * TVL ({@code totalTvlX + totalTvlY}) descending. Note this sums the two
     * raw token amounts directly without converting to a common currency, so it
     * is a rough size proxy rather than a true TVL ranking.
     *
     * @param pageable page/size (sort is fixed by the query)
     * @return one page of the highest-TVL active pools, largest first
     */
    @Query("SELECT p FROM LiquidityPool p WHERE p.status = 'ACTIVE' " +
            "ORDER BY (p.totalTvlX + p.totalTvlY) DESC")
    Page<LiquidityPool> findTopByTvl(Pageable pageable);
}
