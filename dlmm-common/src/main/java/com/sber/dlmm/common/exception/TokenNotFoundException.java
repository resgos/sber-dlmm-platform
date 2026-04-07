package com.sber.dlmm.common.exception;

public class TokenNotFoundException extends DlmmException {
    public TokenNotFoundException(String message) {
        super(message, "TOKEN_NOT_FOUND", 404);
    }
}
