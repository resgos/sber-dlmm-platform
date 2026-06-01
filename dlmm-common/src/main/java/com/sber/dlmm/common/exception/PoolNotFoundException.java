package com.sber.dlmm.common.exception;

/**
 * Thrown when a request references a liquidity pool id that does not exist in the pool-engine
 * store — e.g. a lookup, swap, or liquidity operation against an unknown or deleted pool.
 *
 * <p>Rendered by {@link GlobalExceptionHandler} as <b>HTTP 404 Not Found</b> with error code
 * {@code "POOL_NOT_FOUND"}.
 */
public class PoolNotFoundException extends DlmmException {
    /**
     * @param message human-readable detail (typically the missing pool id) surfaced in the error body
     */
    public PoolNotFoundException(String message) {
        super(message, "POOL_NOT_FOUND", 404);
    }
}
