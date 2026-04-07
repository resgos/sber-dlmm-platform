package com.sber.dlmm.common.exception;

public class TransactionFailedException extends DlmmException {
    public TransactionFailedException(String message) {
        super(message, "TX_FAILED", 500);
    }

    public TransactionFailedException(String message, Throwable cause) {
        super(message, "TX_FAILED", 500, cause);
    }
}
