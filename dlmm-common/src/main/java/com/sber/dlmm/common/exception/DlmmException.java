package com.sber.dlmm.common.exception;

import lombok.Getter;

@Getter
public abstract class DlmmException extends RuntimeException {

    private final String errorCode;
    private final int httpStatus;

    protected DlmmException(String message, String errorCode, int httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    protected DlmmException(String message, String errorCode, int httpStatus, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }
}
