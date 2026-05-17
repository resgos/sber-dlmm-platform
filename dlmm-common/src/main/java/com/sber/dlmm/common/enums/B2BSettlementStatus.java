package com.sber.dlmm.common.enums;

/**
 * Sprint 4 #4.6 — lifecycle of a B2B settlement (corp-to-corp transfer
 * via DLMM token rails).
 *
 * <ul>
 *   <li>{@link #PENDING} — row inserted, token-service calls not started yet.
 *       Visible to the caller so accountant can poll.</li>
 *   <li>{@link #COMPLETED} — both deduct + credit acknowledged. {@code completed_at}
 *       set, audit trail closed.</li>
 *   <li>{@link #FAILED} — either deduct rejected (no balance change) or, worse,
 *       credit failed after deduct succeeded. The latter requires manual
 *       reconciliation; {@code error_message} carries the diagnostic. A proper
 *       saga / compensating-action design is Sprint 5+ work.</li>
 * </ul>
 */
public enum B2BSettlementStatus {
    PENDING,
    COMPLETED,
    FAILED
}
