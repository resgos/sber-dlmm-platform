package com.sber.dlmm.common.exception;

public class InsufficientLiquidityException extends DlmmException {
    public InsufficientLiquidityException(String message) {
        super(message, "INSUFFICIENT_LIQUIDITY", 400);
    }
}
