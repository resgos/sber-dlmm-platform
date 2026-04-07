package com.sber.dlmm.fee.repository;

import com.sber.dlmm.fee.entity.FeeAccrual;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface FeeAccrualRepository extends JpaRepository<FeeAccrual, UUID> {

    List<FeeAccrual> findByUserId(UUID userId);

    List<FeeAccrual> findByPositionId(UUID positionId);

    List<FeeAccrual> findByPoolIdAndUserId(UUID poolId, UUID userId);

    List<FeeAccrual> findByUserIdAndClaimedFalse(UUID userId);

    Page<FeeAccrual> findByUserId(UUID userId, Pageable pageable);

    Page<FeeAccrual> findByUserIdAndPoolId(UUID userId, UUID poolId, Pageable pageable);
}
