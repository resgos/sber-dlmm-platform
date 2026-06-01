package com.sber.dlmm.common.exception;

/**
 * Thrown when a swap's realized output falls short of the caller's minimum-out (or its price moves
 * beyond the supplied slippage tolerance) because pool state shifted between quote and execution.
 * The swap is rejected and rolled back rather than filled at a worse-than-accepted price, protecting
 * the user from front-running and adverse drift.
 *
 * <p>Rendered by {@link GlobalExceptionHandler} as <b>HTTP 400</b> with error code
 * {@code "SLIPPAGE_EXCEEDED"}.
 */
public class SlippageExceededException extends DlmmException {
    /**
     * @param message human-readable detail (e.g. expected vs. minimum-acceptable output) surfaced in the error body
     */
    public SlippageExceededException(String message) {
        super(message, "SLIPPAGE_EXCEEDED", 400);
    }
}
