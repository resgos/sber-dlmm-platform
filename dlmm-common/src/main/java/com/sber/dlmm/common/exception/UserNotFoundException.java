package com.sber.dlmm.common.exception;

public class UserNotFoundException extends DlmmException {
    public UserNotFoundException(String message) {
        super(message, "USER_NOT_FOUND", 404);
    }
}
