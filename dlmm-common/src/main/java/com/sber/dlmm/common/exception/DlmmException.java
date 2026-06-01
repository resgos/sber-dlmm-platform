package com.sber.dlmm.common.exception;

import lombok.Getter;

/**
 * Abstract base for every domain exception on the platform.
 *
 * <p>Each subclass carries two pieces of transport metadata alongside the human-readable
 * {@code message}: a stable machine-readable {@code errorCode} (e.g. {@code "POOL_NOT_FOUND"})
 * that the frontend can switch on, and the {@code httpStatus} the client should receive.
 * {@link GlobalExceptionHandler} catches any {@code DlmmException} thrown out of a controller
 * and renders it into the standard {@link com.sber.dlmm.common.dto.ErrorResponse} body
 * ({@code errorCode}, {@code message}, {@code timestamp}, {@code traceId}) with that status —
 * so business code never hand-builds an error {@code ResponseEntity}; it just throws.
 *
 * <p>Extends {@link RuntimeException} (unchecked) so it can propagate out of service/repository
 * layers without {@code throws} clauses. Annotated with Lombok {@link Getter}, which generates
 * {@code getErrorCode()} and {@code getHttpStatus()} read by the handler.
 *
 * <p>Subclasses are expected to fix their {@code errorCode} and {@code httpStatus} in their own
 * constructors so call sites only need to supply a message.
 */
@Getter
public abstract class DlmmException extends RuntimeException {

    /** Stable machine-readable error code surfaced to clients (e.g. {@code "POOL_NOT_FOUND"}); set by the subclass, never localized. */
    private final String errorCode;

    /** HTTP status code {@link GlobalExceptionHandler} applies to the response (e.g. 404, 400, 403, 409, 503). */
    private final int httpStatus;

    /**
     * Creates an exception with no underlying cause.
     *
     * @param message    human-readable detail; rendered verbatim into the {@code message} field of the error body
     * @param errorCode  stable machine-readable code clients can switch on (e.g. {@code "INSUFFICIENT_BALANCE"})
     * @param httpStatus HTTP status the client receives (e.g. 404, 400, 403, 409, 503)
     */
    protected DlmmException(String message, String errorCode, int httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    /**
     * Creates an exception that wraps a lower-level cause (e.g. a downstream client failure),
     * preserving the original stack trace for logging while still mapping to a clean HTTP response.
     *
     * @param message    human-readable detail; rendered verbatim into the {@code message} field of the error body
     * @param errorCode  stable machine-readable code clients can switch on (e.g. {@code "TX_FAILED"})
     * @param httpStatus HTTP status the client receives (e.g. 404, 400, 403, 409, 503)
     * @param cause      the underlying throwable that triggered this exception, retained for the stack trace
     */
    protected DlmmException(String message, String errorCode, int httpStatus, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }
}
