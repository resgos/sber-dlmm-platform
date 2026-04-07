package com.sber.dlmm.common.exception;

public class PoolNotActiveException extends DlmmException {
    public PoolNotActiveException(String message) {
        super(message, "POOL_NOT_ACTIVE", 400);
    }
}
