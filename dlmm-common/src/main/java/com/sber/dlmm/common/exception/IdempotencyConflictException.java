package com.sber.dlmm.common.exception;

public class IdempotencyConflictException extends DlmmException {
    public IdempotencyConflictException(String message) {
        super(message, "IDEMPOTENCY_CONFLICT", 409);
    }
}
