package com.sber.dlmm.common.exception;

public class InvalidBinRangeException extends DlmmException {
    public InvalidBinRangeException(String message) {
        super(message, "INVALID_BIN_RANGE", 400);
    }
}
