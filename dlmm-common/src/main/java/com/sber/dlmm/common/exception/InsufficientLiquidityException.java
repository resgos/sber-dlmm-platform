package com.sber.dlmm.common.exception;

/**
 * Thrown when a swap cannot be fully filled because the pool's bins do not hold enough reserves of
 * the output token across the traversable price range — the order would exhaust available liquidity
 * before reaching the requested amount. Distinct from {@link InsufficientBalanceException}, which is
 * about the <em>caller's</em> funds rather than the <em>pool's</em> depth.
 *
 * <p>Rendered by {@link GlobalExceptionHandler} as <b>HTTP 400</b> with error code
 * {@code "INSUFFICIENT_LIQUIDITY"}.
 */
public class InsufficientLiquidityException extends DlmmException {
    /**
     * @param message human-readable detail about the liquidity shortfall (surfaced in the error body)
     */
    public InsufficientLiquidityException(String message) {
        super(message, "INSUFFICIENT_LIQUIDITY", 400);
    }
}
