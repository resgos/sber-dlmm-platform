package com.sber.dlmm.pool.repository;

import com.sber.dlmm.pool.entity.PoolPositionReward;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PoolPositionRewardRepository extends JpaRepository<PoolPositionReward, UUID> {

    /** The single reward row for a position (one row per position). */
    Optional<PoolPositionReward> findByPositionId(UUID positionId);

    List<PoolPositionReward> findByUserId(UUID userId);

    /** A user's reward rows that still have something to claim. */
    List<PoolPositionReward> findByUserIdAndUnclaimedRewardGreaterThan(UUID userId, long threshold);
}
