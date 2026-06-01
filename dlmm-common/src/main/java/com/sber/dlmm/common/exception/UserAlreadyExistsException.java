package com.sber.dlmm.common.exception;

/**
 * Thrown during registration when the supplied identifier (typically email) already belongs to an
 * existing account, so a new user cannot be created without colliding with one already on file.
 *
 * <p>Rendered by {@link GlobalExceptionHandler} as <b>HTTP 409 Conflict</b> with error code
 * {@code "USER_EXISTS"}.
 */
public class UserAlreadyExistsException extends DlmmException {
    /**
     * @param message human-readable detail (typically the conflicting email) surfaced in the error body
     */
    public UserAlreadyExistsException(String message) {
        super(message, "USER_EXISTS", 409);
    }
}
