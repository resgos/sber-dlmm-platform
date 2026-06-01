package com.sber.dlmm.common.enums;

/**
 * Operational lifecycle state of a liquidity pool (pool-engine).
 *
 * <p>Gates which operations the engine will accept for a pool: only
 * {@link #ACTIVE} pools take swaps and liquidity adds. The non-active
 * states are administrative controls surfaced in the admin UI.
 */
public enum PoolStatus {
    /** Fully operational — swaps and liquidity operations are accepted. */
    ACTIVE,
    /** Temporarily halted by an admin; can be returned to {@link #ACTIVE}. */
    PAUSED,
    /** Halted via the emergency kill-switch (incident response). Distinct
     *  from {@link #PAUSED} to signal an abnormal, alert-worthy condition. */
    EMERGENCY_SHUTDOWN,
    /** Permanently retired; terminal state, the pool no longer trades. */
    CLOSED
}
