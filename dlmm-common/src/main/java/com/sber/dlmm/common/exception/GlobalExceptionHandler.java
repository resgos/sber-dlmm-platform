package com.sber.dlmm.common.exception;

import com.sber.dlmm.common.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Central {@link RestControllerAdvice} that converts exceptions thrown out of any controller in a
 * {@code dlmm-common}-dependent service into the platform's standard error body.
 *
 * <p>Every response shares the {@link ErrorResponse} shape — {@code errorCode}, {@code message},
 * {@code timestamp} (ISO-8601 {@link Instant}) and a freshly generated {@code traceId} (random
 * {@link UUID}) that is also logged, so a client-reported failure can be correlated with the
 * server log line. Because this advice is the single place errors are rendered, business code
 * throws a {@link DlmmException} subclass instead of hand-building a {@code ResponseEntity}.
 *
 * <p>Three handlers, in increasing generality:
 * <ul>
 *   <li>{@link #handleDlmmException(DlmmException)} — domain errors, status taken from the exception.</li>
 *   <li>{@link #handleValidation(MethodArgumentNotValidException)} — bean-validation failures → 400.</li>
 *   <li>{@link #handleGeneric(Exception)} — anything else → 500 with a redacted message.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Maps any {@link DlmmException} (the whole domain-exception family) to its declared HTTP status.
     *
     * <p>The response {@code errorCode} and {@code message} come straight from the exception, and the
     * status is {@link DlmmException#getHttpStatus()} (e.g. 404 for {@code PoolNotFoundException},
     * 400 for {@code InsufficientBalanceException}, 403 for {@code ForbiddenException}, 409 for
     * {@code IdempotencyConflictException}, 503 for {@code OracleUnavailableException}). The full
     * exception is logged at ERROR with the generated {@code traceId} for correlation.
     *
     * @param ex the thrown domain exception carrying the error code and target HTTP status
     * @return the standard {@link ErrorResponse} body, with HTTP status taken from {@code ex}
     */
    @ExceptionHandler(DlmmException.class)
    public ResponseEntity<ErrorResponse> handleDlmmException(DlmmException ex) {
        String traceId = UUID.randomUUID().toString();
        log.error("DlmmException [traceId={}]: {} - {}", traceId, ex.getErrorCode(), ex.getMessage(), ex);
        ErrorResponse response = new ErrorResponse(
                ex.getErrorCode(),
                ex.getMessage(),
                Instant.now().toString(),
                traceId
        );
        return ResponseEntity.status(ex.getHttpStatus()).body(response);
    }

    /**
     * Maps a bean-validation failure on a {@code @Valid} request body to HTTP 400.
     *
     * <p>Raised by Spring when {@code @Valid}/{@code @Validated} controller arguments fail their
     * constraints. Every field error is flattened into a single {@code "field: message"} list joined
     * by commas, returned under the fixed {@code errorCode} {@code "VALIDATION_ERROR"} so clients can
     * distinguish input-shape problems from domain rejections. Not logged (these are client mistakes,
     * not server faults).
     *
     * @param ex the validation exception; its binding result supplies the per-field error messages
     * @return the standard {@link ErrorResponse} body with code {@code "VALIDATION_ERROR"} and HTTP 400
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String traceId = UUID.randomUUID().toString();
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        ErrorResponse response = new ErrorResponse(
                "VALIDATION_ERROR",
                message,
                Instant.now().toString(),
                traceId
        );
        return ResponseEntity.badRequest().body(response);
    }

    /**
     * Catch-all for any exception not matched by a more specific handler, mapped to HTTP 500.
     *
     * <p>This is the safety net for unexpected/unhandled failures (NPEs, downstream errors that
     * aren't wrapped in a {@link DlmmException}, etc.). The full stack trace is logged at ERROR with
     * the generated {@code traceId}, but the response deliberately returns a generic
     * {@code "Internal server error"} message under code {@code "INTERNAL_ERROR"} so internal
     * details are not leaked to the client — they share the {@code traceId} to enable support lookup.
     *
     * @param ex the unhandled exception (logged in full; not exposed to the client)
     * @return the standard {@link ErrorResponse} body with code {@code "INTERNAL_ERROR"} and HTTP 500
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        String traceId = UUID.randomUUID().toString();
        log.error("Unhandled exception [traceId={}]: {}", traceId, ex.getMessage(), ex);
        ErrorResponse response = new ErrorResponse(
                "INTERNAL_ERROR",
                "Internal server error",
                Instant.now().toString(),
                traceId
        );
        return ResponseEntity.internalServerError().body(response);
    }
}
