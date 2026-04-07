package com.sber.dlmm.common.exception;

public class UserAlreadyExistsException extends DlmmException {
    public UserAlreadyExistsException(String message) {
        super(message, "USER_EXISTS", 409);
    }
}
