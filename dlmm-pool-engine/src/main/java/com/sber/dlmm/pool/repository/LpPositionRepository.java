package com.sber.dlmm.pool.repository;

import com.sber.dlmm.pool.entity.LpPosition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for the {@link LpPosition} aggregate — each user's liquidity
 * positions. Beyond CRUD it serves "my positions" listings, per-pool position
 * scans, and the margin-call scanner's active-position pagination.
 */
@Repository
public interface LpPositionRepository extends JpaRepository<LpPosition, UUID> {

    /**
     * A user's currently open positions ({@code isActive = true}) — the
     * user-ui "My positions" list.
     *
     * @param userId owner to filter on
     * @return the user's active positions (may be empty)
     */
    List<LpPosition> findByUserIdAndIsActiveTrue(UUID userId);

    /**
     * All open positions in a pool — e.g. to fan a pool-wide fee/reward accrual
     * across its in-range LPs.
     *
     * @param poolId pool to filter on
     * @return the pool's active positions (may be empty)
     */
    List<LpPosition> findByPoolIdAndIsActiveTrue(UUID poolId);

    /**
     * Every position a user has ever held, active or closed (full history).
     *
     * @param userId owner to filter on
     * @return all of the user's positions (may be empty)
     */
    List<LpPosition> findByUserId(UUID userId);

    /**
     * Pageable listing of a pool's positions (active and closed) for admin
     * drill-down.
     *
     * @param poolId   pool to filter on
     * @param pageable page/size/sort
     * @return one page of the pool's positions
     */
    Page<LpPosition> findByPoolId(UUID poolId, Pageable pageable);

    /**
     * Count of all open positions platform-wide — a dashboard metric.
     *
     * @return number of positions with {@code isActive = true}
     */
    long countByIsActiveTrue();

    /**
     * Sprint 4 #4.3 — page through all active positions for the margin-call
     * scanner. Pageable so a 100k-position prod load doesn't get loaded
     * into memory in one shot; scanner walks pages of 500.
     *
     * @param pageable page/size (scanner uses ~500 per page)
     * @return one page of active positions platform-wide
     */
    Page<LpPosition> findByIsActiveTrue(Pageable pageable);
}
