package com.sber.dlmm.common.exception;

/**
 * Thrown when a lookup references a user id/email that does not exist — e.g. login for an unknown
 * account, a profile/KYC fetch, or an inter-service identity resolution that finds no matching user.
 *
 * <p>Rendered by {@link GlobalExceptionHandler} as <b>HTTP 404 Not Found</b> with error code
 * {@code "USER_NOT_FOUND"}.
 */
public class UserNotFoundException extends DlmmException {
    /**
     * @param message human-readable detail (typically the missing user id or email) surfaced in the error body
     */
    public UserNotFoundException(String message) {
        super(message, "USER_NOT_FOUND", 404);
    }
}
