package com.sber.dlmm.common.exception;

/**
 * Thrown when an authenticated caller is denied an action they lack permission for — i.e. identity
 * is known but authority is insufficient (wrong role, KYC tier too low, resource not owned by the
 * caller). Use this rather than {@link UnauthorizedException}, which is for missing/invalid
 * credentials.
 *
 * <p>Rendered by {@link GlobalExceptionHandler} as <b>HTTP 403</b> with error code {@code "FORBIDDEN"}.
 */
public class ForbiddenException extends DlmmException {
    /**
     * @param message human-readable reason the action was denied (surfaced in the error body)
     */
    public ForbiddenException(String message) {
        super(message, "FORBIDDEN", 403);
    }
}
