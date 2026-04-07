package com.sber.dlmm.common.exception;

public class InsufficientBalanceException extends DlmmException {
    public InsufficientBalanceException(String message) {
        super(message, "INSUFFICIENT_BALANCE", 400);
    }
}
