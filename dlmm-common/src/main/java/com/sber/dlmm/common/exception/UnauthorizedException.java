package com.sber.dlmm.common.exception;

/**
 * Thrown when a request lacks valid authentication — no credentials, a malformed/expired JWT, bad
 * login credentials, or a revoked token — so the caller's identity cannot be established. Contrast
 * {@link ForbiddenException}, which applies when identity <em>is</em> known but lacks the required
 * authority.
 *
 * <p>Rendered by {@link GlobalExceptionHandler} as <b>HTTP 401 Unauthorized</b> with error code
 * {@code "UNAUTHORIZED"}.
 */
public class UnauthorizedException extends DlmmException {
    /**
     * @param message human-readable reason authentication failed (surfaced in the error body)
     */
    public UnauthorizedException(String message) {
        super(message, "UNAUTHORIZED", 401);
    }
}
