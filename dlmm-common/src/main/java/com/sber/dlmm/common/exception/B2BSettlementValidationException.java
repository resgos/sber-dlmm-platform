package com.sber.dlmm.common.exception;

/**
 * Sprint 4 #4.6 — thrown for shape-level rejections of a B2B settlement
 * request (e.g. counterparty == initiator, unknown counterparty UUID,
 * non-positive amount that slipped past bean validation). Caller sees
 * HTTP 400 with code B2B_SETTLEMENT_INVALID.
 *
 * <p>Distinct from {@link InsufficientBalanceException} (raised by
 * token-service when the deduct call rejects on balance) and from
 * partial-failure FAILED status (which is surfaced via the response
 * body, not an exception — the audit row still exists).
 */
public class B2BSettlementValidationException extends DlmmException {
    /**
     * @param message human-readable detail describing the rejected settlement shape (surfaced in the error body)
     */
    public B2BSettlementValidationException(String message) {
        super(message, "B2B_SETTLEMENT_INVALID", 400);
    }
}
