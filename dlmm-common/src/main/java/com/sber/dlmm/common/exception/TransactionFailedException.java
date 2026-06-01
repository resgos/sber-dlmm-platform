package com.sber.dlmm.common.exception;

/**
 * Thrown when a transaction/settlement could not be completed for an unexpected reason — a downstream
 * service error, a ledger write failure, or an irrecoverable inconsistency mid-flow — as opposed to a
 * clean business rejection (which uses a more specific 4xx exception). Indicates a server-side fault,
 * so the caller cannot fix it by changing input.
 *
 * <p>Rendered by {@link GlobalExceptionHandler} as <b>HTTP 500 Internal Server Error</b> with error
 * code {@code "TX_FAILED"}.
 */
public class TransactionFailedException extends DlmmException {
    /**
     * @param message human-readable detail about the failure (surfaced in the error body)
     */
    public TransactionFailedException(String message) {
        super(message, "TX_FAILED", 500);
    }

    /**
     * Wraps the underlying throwable that caused the failure so it is retained in the stack trace and
     * server logs (the cause is not exposed to the client).
     *
     * @param message human-readable detail about the failure (surfaced in the error body)
     * @param cause   the lower-level exception that triggered this failure
     */
    public TransactionFailedException(String message, Throwable cause) {
        super(message, "TX_FAILED", 500, cause);
    }
}
