package com.sber.dlmm.common.exception;

public class PoolNotFoundException extends DlmmException {
    public PoolNotFoundException(String message) {
        super(message, "POOL_NOT_FOUND", 404);
    }
}
