package com.sber.dlmm.common.exception;

/**
 * Thrown when a request references a token (by id or symbol) that is not present in the token-service
 * catalog — e.g. a balance, quote, or pool-creation call naming an unknown asset.
 *
 * <p>Rendered by {@link GlobalExceptionHandler} as <b>HTTP 404 Not Found</b> with error code
 * {@code "TOKEN_NOT_FOUND"}.
 */
public class TokenNotFoundException extends DlmmException {
    /**
     * @param message human-readable detail (typically the missing token id or symbol) surfaced in the error body
     */
    public TokenNotFoundException(String message) {
        super(message, "TOKEN_NOT_FOUND", 404);
    }
}
