package com.sber.dlmm.pool.repository;

import com.sber.dlmm.pool.entity.PoolPositionReward;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link PoolPositionReward} — accrued LP-farming reward per
 * position (one row per position). The accrual job UPSERTs by position; a claim
 * aggregates a user's rows.
 */
public interface PoolPositionRewardRepository extends JpaRepository<PoolPositionReward, UUID> {

    /**
     * The single reward row for a position (one row per position); the accrual
     * job's UPSERT lookup.
     *
     * @param positionId position to look up
     * @return its reward row, or empty if nothing has accrued yet
     */
    Optional<PoolPositionReward> findByPositionId(UUID positionId);

    /**
     * All of a user's reward rows across their positions — the basis for a
     * full claim and for displaying total accrued reward.
     *
     * @param userId owner to filter on
     * @return the user's reward rows (may be empty)
     */
    List<PoolPositionReward> findByUserId(UUID userId);

    /**
     * A user's reward rows that still have something to claim (unclaimed reward
     * strictly above the threshold — pass 0 to get all non-empty rows). Lets a
     * claim skip already-drained rows.
     *
     * @param userId    owner to filter on
     * @param threshold exclusive lower bound on {@code unclaimedReward} (raw units)
     * @return the user's rows with claimable reward (may be empty)
     */
    List<PoolPositionReward> findByUserIdAndUnclaimedRewardGreaterThan(UUID userId, long threshold);
}
