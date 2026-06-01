package com.sber.dlmm.common.exception;

/**
 * Thrown when an operation targets a pool that exists but is not in an {@code ACTIVE} state — e.g. it
 * is paused, halted, or pending activation — so trading or liquidity changes against it are not
 * permitted. The pool was found (contrast {@link PoolNotFoundException}); it is simply not open for
 * the requested action.
 *
 * <p>Rendered by {@link GlobalExceptionHandler} as <b>HTTP 400</b> with error code
 * {@code "POOL_NOT_ACTIVE"}.
 */
public class PoolNotActiveException extends DlmmException {
    /**
     * @param message human-readable detail (typically the pool id and its current state) surfaced in the error body
     */
    public PoolNotActiveException(String message) {
        super(message, "POOL_NOT_ACTIVE", 400);
    }
}
