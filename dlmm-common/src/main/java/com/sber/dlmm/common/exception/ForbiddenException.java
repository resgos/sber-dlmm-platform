package com.sber.dlmm.common.exception;

public class ForbiddenException extends DlmmException {
    public ForbiddenException(String message) {
        super(message, "FORBIDDEN", 403);
    }
}
