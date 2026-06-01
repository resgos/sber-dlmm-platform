package com.sber.dlmm.user.service;

import com.sber.dlmm.common.exception.DlmmException;

/**
 * Sprint 11 G-20 — local 2FA exception type. Extends {@link DlmmException}
 * so {@code GlobalExceptionHandler} renders it as a structured
 * {@code ErrorResponse}; HTTP status varies by error code (see static
 * factories below).
 *
 * <p>Service-local rather than in {@code dlmm-common} because the 2FA
 * vocabulary doesn't (yet) need to be shared across services — only
 * user-service issues these.
 */
public class TwoFactorException extends DlmmException {

    /**
     * Private base constructor — instances are created only through the named
     * static factories below, which fix the message / error code / HTTP status
     * for each distinct 2FA failure. Keeps construction self-documenting at the
     * call site and prevents ad-hoc codes.
     *
     * @param message    human-readable detail rendered into the error body
     * @param errorCode  stable machine code (e.g. {@code TWO_FACTOR_INVALID_CODE})
     * @param httpStatus the HTTP status {@code GlobalExceptionHandler} should return
     */
    private TwoFactorException(String message, String errorCode, int httpStatus) {
        super(message, errorCode, httpStatus);
    }

    /**
     * 400 — caller-supplied input (secret, recovery codes payload) didn't
     * match what {@code /begin} returned, or was structurally malformed.
     */
    public static TwoFactorException invalidSetupPayload(String reason) {
        return new TwoFactorException(
                "Invalid 2FA setup payload: " + reason,
                "TWO_FACTOR_INVALID_SETUP",
                400);
    }

    /**
     * 400 — TOTP code presented during {@code /enable} didn't validate.
     * Login-flow {@code /verify} uses 401 instead (see {@link #verifyFailed}).
     */
    public static TwoFactorException invalidEnableCode() {
        return new TwoFactorException(
                "TOTP code did not validate — check that your authenticator app's time is in sync",
                "TWO_FACTOR_INVALID_CODE",
                400);
    }

    /**
     * 401 — login-flow verify failed. Distinct error code from
     * {@link #invalidEnableCode} so the gateway can shape a different
     * client-facing message on each path (e.g. "wrong code" vs.
     * "wrong code, X attempts left").
     */
    public static TwoFactorException verifyFailed() {
        return new TwoFactorException(
                "2FA verification failed",
                "TWO_FACTOR_VERIFY_FAILED",
                401);
    }

    /**
     * 409 — caller tried to enable 2FA when it's already enabled.
     * Disabling first is required (so we don't silently rotate the
     * secret out from under the user's authenticator app).
     */
    public static TwoFactorException alreadyEnabled() {
        return new TwoFactorException(
                "2FA is already enabled — disable it first to re-enrol",
                "TWO_FACTOR_ALREADY_ENABLED",
                409);
    }

    /**
     * 409 — verify called on a user who hasn't enabled 2FA. Login flow
     * should call {@code /status} first; receiving this means the
     * client UI is out of sync.
     */
    public static TwoFactorException notEnabled() {
        return new TwoFactorException(
                "2FA is not enabled for this user",
                "TWO_FACTOR_NOT_ENABLED",
                409);
    }
}
