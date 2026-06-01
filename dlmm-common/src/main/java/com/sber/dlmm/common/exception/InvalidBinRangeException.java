package com.sber.dlmm.common.exception;

/**
 * Thrown when an add-/remove-liquidity request specifies a malformed bin range — e.g. lower bin id
 * greater than upper, a span exceeding the per-position bin cap, or ids that fall outside the pool's
 * valid bin grid. Guards the DLMM bin model against positions that cannot be placed.
 *
 * <p>Rendered by {@link GlobalExceptionHandler} as <b>HTTP 400</b> with error code
 * {@code "INVALID_BIN_RANGE"}.
 */
public class InvalidBinRangeException extends DlmmException {
    /**
     * @param message human-readable detail about why the bin range is invalid (surfaced in the error body)
     */
    public InvalidBinRangeException(String message) {
        super(message, "INVALID_BIN_RANGE", 400);
    }
}
