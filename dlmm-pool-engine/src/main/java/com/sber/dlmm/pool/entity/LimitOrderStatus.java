package com.sber.dlmm.pool.entity;

/**
 * Lifecycle of a {@link LimitOrder}. OPEN → (FILLED | CANCELLED); terminal once
 * it leaves OPEN. There is no PARTIALLY_FILLED state — our escrow-settled orders
 * fill atomically at the limit price the moment the pool price crosses it.
 */
public enum LimitOrderStatus {
    OPEN,
    FILLED,
    CANCELLED
}
