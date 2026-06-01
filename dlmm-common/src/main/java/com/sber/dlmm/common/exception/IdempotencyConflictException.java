package com.sber.dlmm.common.exception;

/**
 * Thrown when a request reuses an idempotency key that is already in flight or was already completed
 * with different parameters, so honouring it would risk a duplicate mutation (e.g. a retried swap or
 * settlement whose payload no longer matches the first call's). Signals the client to stop retrying
 * and reconcile rather than re-send.
 *
 * <p>Rendered by {@link GlobalExceptionHandler} as <b>HTTP 409 Conflict</b> with error code
 * {@code "IDEMPOTENCY_CONFLICT"}.
 */
public class IdempotencyConflictException extends DlmmException {
    /**
     * @param message human-readable description of the idempotency-key collision (surfaced in the error body)
     */
    public IdempotencyConflictException(String message) {
        super(message, "IDEMPOTENCY_CONFLICT", 409);
    }
}
