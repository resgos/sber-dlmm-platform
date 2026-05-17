package com.sber.dlmm.common.exception;

/**
 * Sprint 4 #4.2 — thrown when a single swap exceeds the per-pool
 * counterparty exposure cap (max_single_swap_nominal_x/y on
 * liquidity_pools). Caller sees HTTP 400 with code COUNTERPARTY_LIMIT_EXCEEDED.
 */
public class CounterpartyLimitExceededException extends DlmmException {
    public CounterpartyLimitExceededException(String message) {
        super(message, "COUNTERPARTY_LIMIT_EXCEEDED", 400);
    }
}
