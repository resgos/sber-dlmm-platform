package com.sber.dlmm.pool.repository;

import com.sber.dlmm.pool.entity.LimitOrder;
import com.sber.dlmm.pool.entity.LimitOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LimitOrderRepository extends JpaRepository<LimitOrder, UUID> {

    List<LimitOrder> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<LimitOrder> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, LimitOrderStatus status);

    List<LimitOrder> findByUserIdAndPoolIdOrderByCreatedAtDesc(UUID userId, UUID poolId);

    /** All open orders — the fill watcher's work-list. */
    List<LimitOrder> findByStatus(LimitOrderStatus status);

    Optional<LimitOrder> findByIdempotencyKey(String idempotencyKey);
}
