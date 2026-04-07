package com.sber.dlmm.common.exception;

public class OracleUnavailableException extends DlmmException {
    public OracleUnavailableException(String message) {
        super(message, "ORACLE_UNAVAILABLE", 503);
    }
}
