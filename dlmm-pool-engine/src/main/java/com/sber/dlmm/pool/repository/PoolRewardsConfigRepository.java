package com.sber.dlmm.pool.repository;

import com.sber.dlmm.pool.entity.PoolRewardsConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link PoolRewardsConfig} — the per-pool LP-farming emission
 * settings (one row per pool). Read by the farming scheduler and the admin
 * config UI.
 */
public interface PoolRewardsConfigRepository extends JpaRepository<PoolRewardsConfig, UUID> {

    /**
     * Every pool with farming switched on — the accrual scheduler's work-list.
     *
     * @return enabled reward configs (may be empty)
     */
    List<PoolRewardsConfig> findByEnabledTrue();

    /**
     * The farming config for one pool (pool_id is unique).
     *
     * @param poolId pool to look up
     * @return the pool's config, or empty if farming was never configured for it
     */
    Optional<PoolRewardsConfig> findByPoolId(UUID poolId);
}
