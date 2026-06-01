package com.sber.dlmm.common.dto;

/**
 * Standard error body returned to API clients across all services.
 *
 * <p>Rendered by the shared {@code GlobalExceptionHandler} (in
 * {@code dlmm-common}) so every service emits an identically-shaped error
 * payload; callers should not hand-build error {@code ResponseEntity}s.
 *
 * @param errorCode stable machine-readable code (e.g. from a {@code DlmmException})
 *                  that clients can branch on without parsing {@code message}
 * @param message   human-readable description of what went wrong
 * @param timestamp when the error was produced (ISO-8601 string)
 * @param traceId   correlation id for cross-service log lookup of this failure
 */
public record ErrorResponse(
        String errorCode,
        String message,
        String timestamp,
        String traceId
) {}
