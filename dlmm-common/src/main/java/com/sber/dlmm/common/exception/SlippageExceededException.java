package com.sber.dlmm.common.exception;

public class SlippageExceededException extends DlmmException {
    public SlippageExceededException(String message) {
        super(message, "SLIPPAGE_EXCEEDED", 400);
    }
}
