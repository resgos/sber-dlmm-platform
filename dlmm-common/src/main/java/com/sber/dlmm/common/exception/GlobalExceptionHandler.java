package com.sber.dlmm.common.exception;

import com.sber.dlmm.common.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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
 * <p>Handlers, in increasing generality:
 * <ul>
 *   <li>{@link #handleDlmmException(DlmmException)} — domain errors, status taken from the exception.</li>
 *   <li>{@link #handleValidation(MethodArgumentNotValidException)} — bean-validation failures → 400.</li>
 *   <li>{@link #handleTypeMismatch(MethodArgumentTypeMismatchException)} — param type conversion → 400.</li>
 *   <li>{@link #handleGeneric(Exception)} — framework exceptions carrying a 4xx status
 *       (unknown path/405/415 via {@code org.springframework.web.ErrorResponse}) keep it;
 *       anything else → 500 with a redacted message.</li>
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
     * Maps a path/query parameter that failed type conversion (e.g. {@code "overview"} hitting a
     * {@code UUID} path variable) to HTTP 400.
     *
     * <p>Without this, Spring's {@link MethodArgumentTypeMismatchException} fell through to the
     * 500 catch-all and was logged at ERROR with a full stack — a fat-fingered URL read as a server
     * fault (found live 2026-06-12: {@code /admin/pools/overview} → 500 "Invalid UUID string").
     * It's a client mistake, so: 400, {@code VALIDATION_ERROR}, INFO log without a stack.
     *
     * @param ex the conversion failure; its parameter name feeds the client-facing message
     * @return the standard {@link ErrorResponse} body with code {@code "VALIDATION_ERROR"} and HTTP 400
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String traceId = UUID.randomUUID().toString();
        String wanted = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "value";
        String message = String.format("Параметр '%s' имеет неверный формат (ожидается %s)",
                ex.getName(), wanted);
        log.info("Type mismatch [traceId={}]: {} = '{}' (wanted {})", traceId, ex.getName(), ex.getValue(), wanted);
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

        // Spring 6 marks framework exceptions that ALREADY know their HTTP status
        // (unknown path → NoResourceFoundException/404, method not allowed → 405,
        // unsupported media type → 415, …) with the org.springframework.web.ErrorResponse
        // interface. Before this branch they all fell into the 500 catch-all and were
        // logged at ERROR with a stack — a mistyped URL read as a server fault (found
        // live 2026-06-12: GET /api/v1/admin/stats → 500 "No static resource").
        // Honour the framework's status; these are client errors → INFO, no stack.
        // (Interface check keeps dlmm-common off spring-webmvc, which the reactive
        // gateway doesn't ship.)
        if (ex instanceof org.springframework.web.ErrorResponse er) {
            HttpStatus status = HttpStatus.resolve(er.getStatusCode().value());
            if (status != null && status.is4xxClientError()) {
                log.info("Client error [traceId={}]: {} {}", traceId, status.value(), ex.getMessage());
                ErrorResponse response = new ErrorResponse(
                        status == HttpStatus.NOT_FOUND ? "NOT_FOUND" : "CLIENT_ERROR",
                        status == HttpStatus.NOT_FOUND ? "Ресурс не найден" : "Некорректный запрос",
                        Instant.now().toString(),
                        traceId
                );
                return ResponseEntity.status(status).body(response);
            }
        }

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
