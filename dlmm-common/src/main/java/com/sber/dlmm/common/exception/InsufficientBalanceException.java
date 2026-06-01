package com.sber.dlmm.common.exception;

/**
 * Thrown when a user's available token balance is too low to cover a requested debit — e.g. a swap
 * input, an add-liquidity deposit, or a B2B settlement amount that exceeds the spendable balance
 * held by token-service. Raised by the internal deduct path before any funds move.
 *
 * <p>Rendered by {@link GlobalExceptionHandler} as <b>HTTP 400</b> with error code
 * {@code "INSUFFICIENT_BALANCE"}.
 */
public class InsufficientBalanceException extends DlmmException {
    /**
     * @param message human-readable detail (typically the token and the shortfall) surfaced in the error body
     */
    public InsufficientBalanceException(String message) {
        super(message, "INSUFFICIENT_BALANCE", 400);
    }
}
