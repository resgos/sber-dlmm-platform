package com.sber.dlmm.pool.repository;

import com.sber.dlmm.pool.entity.PoolRewardsConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PoolRewardsConfigRepository extends JpaRepository<PoolRewardsConfig, UUID> {

    /** Every pool with farming switched on — the accrual scheduler's work-list. */
    List<PoolRewardsConfig> findByEnabledTrue();

    Optional<PoolRewardsConfig> findByPoolId(UUID poolId);
}
