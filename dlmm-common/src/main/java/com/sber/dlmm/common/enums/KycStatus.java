package com.sber.dlmm.common.enums;

/**
 * Know-Your-Customer verification state of a user account.
 *
 * <p>Persisted on the user row (user-service) and propagated to every
 * downstream service via the {@code X-Kyc-Status} header that the gateway
 * injects from the JWT {@code kycStatus} claim. KYC-gated operations
 * (e.g. swaps, liquidity, withdrawals in pool-engine) require
 * {@link #VERIFIED}; pool-engine's KYC lookup is fail-closed, so anything
 * other than {@code VERIFIED} is treated as not allowed.
 */
public enum KycStatus {
    /** Account created; KYC documents not yet submitted. */
    PENDING,
    /** Documents submitted and under review (manual or automated). */
    IN_PROGRESS,
    /** Identity confirmed — the only state that unlocks KYC-gated operations. */
    VERIFIED,
    /** Verification denied; user must resubmit before gaining access. */
    REJECTED
}
