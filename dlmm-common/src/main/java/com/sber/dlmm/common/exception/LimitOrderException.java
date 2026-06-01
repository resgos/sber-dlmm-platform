package com.sber.dlmm.common.exception;

import java.util.UUID;

/**
 * Limit-order domain error (placement validation, illegal state transition,
 * lookup miss). Defaults to HTTP 400; {@link #notFound(UUID)} yields 404.
 *
 * <p>Rendered by {@link GlobalExceptionHandler}: the default constructor maps to <b>HTTP 400</b>
 * with code {@code "LIMIT_ORDER_INVALID"}; {@link #notFound(UUID)} maps to <b>HTTP 404</b> with code
 * {@code "LIMIT_ORDER_NOT_FOUND"}. The three-arg constructor lets callers pin any other code/status.
 */
public class LimitOrderException extends DlmmException {

    /**
     * Creates a generic placement/validation/state error mapped to HTTP 400
     * ({@code "LIMIT_ORDER_INVALID"}).
     *
     * @param message human-readable detail about why the limit-order request is invalid (surfaced in the error body)
     */
    public LimitOrderException(String message) {
        super(message, "LIMIT_ORDER_INVALID", 400);
    }

    /**
     * Escape hatch for variants that need a non-default code/status (used by factories such as
     * {@link #notFound(UUID)}).
     *
     * @param message    human-readable detail surfaced in the error body
     * @param errorCode  stable {@code LIMIT_ORDER_*} code clients switch on
     * @param httpStatus HTTP status the client receives
     */
    public LimitOrderException(String message, String errorCode, int httpStatus) {
        super(message, errorCode, httpStatus);
    }

    /**
     * Factory for the lookup-miss case: a limit order with the given id does not exist. Produces an
     * exception mapped to HTTP 404 with code {@code "LIMIT_ORDER_NOT_FOUND"} and a message embedding
     * the id.
     *
     * @param id the missing limit-order id, interpolated into the message
     * @return a {@code LIMIT_ORDER_NOT_FOUND} / HTTP 404 exception ready to throw
     */
    public static LimitOrderException notFound(UUID id) {
        return new LimitOrderException("Limit order not found: " + id, "LIMIT_ORDER_NOT_FOUND", 404);
    }
}
