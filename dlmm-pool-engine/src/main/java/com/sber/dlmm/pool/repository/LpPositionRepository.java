package com.sber.dlmm.pool.repository;

import com.sber.dlmm.pool.entity.LpPosition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LpPositionRepository extends JpaRepository<LpPosition, UUID> {

    List<LpPosition> findByUserIdAndIsActiveTrue(UUID userId);

    List<LpPosition> findByPoolIdAndIsActiveTrue(UUID poolId);

    List<LpPosition> findByUserId(UUID userId);

    Page<LpPosition> findByPoolId(UUID poolId, Pageable pageable);
}
