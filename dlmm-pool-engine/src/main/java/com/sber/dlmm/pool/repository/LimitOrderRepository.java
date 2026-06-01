package com.sber.dlmm.pool.repository;

import com.sber.dlmm.pool.entity.LimitOrder;
import com.sber.dlmm.pool.entity.LimitOrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link LimitOrder} — the escrow-settled DLMM limit orders.
 * Serves the user's order lists (newest first) and the fill watcher's
 * open-order work-list, plus idempotent placement.
 */
public interface LimitOrderRepository extends JpaRepository<LimitOrder, UUID> {

    /**
     * A user's full order history, newest first — the "My orders" list.
     *
     * @param userId owner to filter on
     * @return the user's orders ordered by {@code createdAt} descending (may be empty)
     */
    List<LimitOrder> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * A user's orders in one lifecycle state, newest first (e.g. only OPEN, or
     * only FILLED for history).
     *
     * @param userId owner to filter on
     * @param status lifecycle state to match
     * @return matching orders, newest first (may be empty)
     */
    List<LimitOrder> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, LimitOrderStatus status);

    /**
     * A user's orders within a single pool, newest first — the per-pool order
     * panel.
     *
     * @param userId owner to filter on
     * @param poolId pool to filter on
     * @return matching orders, newest first (may be empty)
     */
    List<LimitOrder> findByUserIdAndPoolIdOrderByCreatedAtDesc(UUID userId, UUID poolId);

    /**
     * All orders in a given state — called with {@code OPEN} by the fill
     * watcher to build its scan work-list each tick.
     *
     * @param status lifecycle state to match (OPEN for the watcher)
     * @return every order in that state across all users/pools (may be empty)
     */
    List<LimitOrder> findByStatus(LimitOrderStatus status);

    /**
     * Looks up a prior order by its caller-supplied idempotency key so a
     * retried placement returns the existing order instead of creating a
     * duplicate.
     *
     * @param idempotencyKey de-dup token sent on create
     * @return the existing order for that key, or empty if this is the first use
     */
    Optional<LimitOrder> findByIdempotencyKey(String idempotencyKey);
}
