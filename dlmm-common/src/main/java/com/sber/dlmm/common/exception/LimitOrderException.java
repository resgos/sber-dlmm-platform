package com.sber.dlmm.common.exception;

import java.util.UUID;

/**
 * Limit-order domain error (placement validation, illegal state transition,
 * lookup miss). Defaults to HTTP 400; {@link #notFound(UUID)} yields 404.
 */
public class LimitOrderException extends DlmmException {

    public LimitOrderException(String message) {
        super(message, "LIMIT_ORDER_INVALID", 400);
    }

    public LimitOrderException(String message, String errorCode, int httpStatus) {
        super(message, errorCode, httpStatus);
    }

    public static LimitOrderException notFound(UUID id) {
        return new LimitOrderException("Limit order not found: " + id, "LIMIT_ORDER_NOT_FOUND", 404);
    }
}
