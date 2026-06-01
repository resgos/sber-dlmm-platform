package com.sber.dlmm.common.exception;

/**
 * Thrown when a price reference cannot be obtained from the price-oracle service — it is down,
 * timing out, or returned no usable feed for the requested token/pool — so an operation that
 * requires a trusted price (e.g. a margin/health check or an oracle-anchored quote) cannot safely
 * proceed. Signals a transient upstream-dependency outage rather than bad input.
 *
 * <p>Rendered by {@link GlobalExceptionHandler} as <b>HTTP 503 Service Unavailable</b> with error
 * code {@code "ORACLE_UNAVAILABLE"}; the client may retry once the oracle recovers.
 */
public class OracleUnavailableException extends DlmmException {
    /**
     * @param message human-readable detail about the oracle failure (surfaced in the error body)
     */
    public OracleUnavailableException(String message) {
        super(message, "ORACLE_UNAVAILABLE", 503);
    }
}
