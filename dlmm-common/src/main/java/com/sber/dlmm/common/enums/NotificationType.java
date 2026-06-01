package com.sber.dlmm.common.enums;

/**
 * Category of a user-facing notification produced by the platform.
 *
 * <p>Set on events that flow through Kafka and are turned into
 * notifications by the notification-service; also drives how the UI
 * renders / groups each alert. Values cover position lifecycle, trading,
 * KYC, pool operations and the LP margin-watch alerts.
 */
public enum NotificationType {
    /** LP fees have accrued on a position. */
    FEE_ACCRUED,
    /** An LP position was fully closed. */
    POSITION_CLOSED,
    /** A swap initiated by the user completed. */
    SWAP_COMPLETED,
    /** The user's KYC verification was approved. */
    KYC_APPROVED,
    /** The user's KYC verification was rejected. */
    KYC_REJECTED,
    /** A pool the user has exposure to was paused by an admin. */
    POOL_PAUSED,
    /** Generic operational / system-wide alert. */
    SYSTEM_ALERT,
    /**
     * Sprint 4 #4.3 — LP-position is active_bin within N bins of going
     * out-of-range. Early warning so the treasurer can rebalance before
     * fees fully stop.
     */
    MARGIN_WARNING,
    /**
     * Sprint 4 #4.3 — LP-position's range no longer contains the active
     * bin. No fees accruing; treasurer holds the single-sided result.
     */
    MARGIN_CALL
}
