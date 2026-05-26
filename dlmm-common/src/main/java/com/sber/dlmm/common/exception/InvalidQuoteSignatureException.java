package com.sber.dlmm.common.exception;

/**
 * Thrown when {@code POST /swap/execute} carries a signature that
 * doesn't match the signature recorded against the quote. Prevents
 * cross-user replay (someone intercepting a quoteId and trying to
 * spend it themselves) and prevents tampering with the quote's
 * parameters between quote-issue and execute.
 *
 * <p>Pinned by {@code SwapIdempotencyTest#rejectsSignatureMismatch}
 * (Batch G-02). Signature here is the user-issued JWT subject hash
 * (or, in the OTC desk pattern, an HMAC over the quoted parameters)
 * — the test treats it as an opaque string so the contract pins
 * "wrong signature ⇒ rejected" without coupling to the chosen scheme.
 */
public class InvalidQuoteSignatureException extends DlmmException {
    public InvalidQuoteSignatureException(String message) {
        super(message, "INVALID_QUOTE_SIGNATURE", 403);
    }
}
