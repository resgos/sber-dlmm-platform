package com.sber.dlmm.pool.repository;

import com.sber.dlmm.pool.entity.PositionBin;
import com.sber.dlmm.pool.entity.PositionBinId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PositionBinRepository extends JpaRepository<PositionBin, PositionBinId> {

    List<PositionBin> findByPositionId(UUID positionId);

    void deleteByPositionId(UUID positionId);
}
