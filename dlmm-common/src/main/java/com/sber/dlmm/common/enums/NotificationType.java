package com.sber.dlmm.common.enums;

public enum NotificationType {
    FEE_ACCRUED,
    POSITION_CLOSED,
    SWAP_COMPLETED,
    KYC_APPROVED,
    KYC_REJECTED,
    POOL_PAUSED,
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
