package com.sber.dlmm.common.exception;

public class UnauthorizedException extends DlmmException {
    public UnauthorizedException(String message) {
        super(message, "UNAUTHORIZED", 401);
    }
}
