package com.sber.dlmm.common.enums;

/**
 * Lifecycle state of a transaction in the transaction-service ledger.
 *
 * <p>A transaction advances roughly in declaration order:
 * {@code CREATED → VALIDATING → PENDING → EXECUTING → CONFIRMED}, branching
 * to a failure terminal state if a step is rejected. {@link #CONFIRMED},
 * {@link #FAILED} and {@link #ROLLED_BACK} are terminal.
 */
public enum TransactionStatus {
    /** Record persisted; processing not yet started. */
    CREATED,
    /** Pre-flight checks running (balances, limits, AML/KYC gates). */
    VALIDATING,
    /** Accepted and queued, awaiting execution. */
    PENDING,
    /** Settlement / on-engine execution in progress. */
    EXECUTING,
    /** Successfully settled — terminal success state. */
    CONFIRMED,
    /** Aborted before settlement (validation or execution error) —
     *  terminal, no balance change committed. */
    FAILED,
    /** Settled effects were reversed by a compensating action —
     *  terminal. */
    ROLLED_BACK
}
