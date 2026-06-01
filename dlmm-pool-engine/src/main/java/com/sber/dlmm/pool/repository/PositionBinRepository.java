package com.sber.dlmm.pool.repository;

import com.sber.dlmm.pool.entity.PositionBin;
import com.sber.dlmm.pool.entity.PositionBinId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link PositionBin} — the per-bin share rows that decompose an
 * {@link com.sber.dlmm.pool.entity.LpPosition} across the bins it spans. Keyed
 * by {@link PositionBinId} (position_id, bin_id).
 */
@Repository
public interface PositionBinRepository extends JpaRepository<PositionBin, PositionBinId> {

    /**
     * All per-bin share rows for one position (its full bin breakdown), used
     * when valuing the position or computing per-bin fee growth.
     *
     * @param positionId position whose bin rows to load
     * @return the position's bin rows (may be empty)
     */
    List<PositionBin> findByPositionId(UUID positionId);

    /**
     * Bulk-deletes every bin row of a position — issued when the position is
     * fully removed/closed so no orphan share rows remain.
     *
     * @param positionId position whose bin rows to delete
     */
    void deleteByPositionId(UUID positionId);
}
