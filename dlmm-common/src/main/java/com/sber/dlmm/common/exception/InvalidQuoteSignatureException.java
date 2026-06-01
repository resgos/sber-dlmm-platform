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
 *
 * <p>Rendered by {@link GlobalExceptionHandler} as <b>HTTP 403</b> with error code
 * {@code "INVALID_QUOTE_SIGNATURE"}.
 */
public class InvalidQuoteSignatureException extends DlmmException {
    /**
     * @param message human-readable detail about the signature mismatch (surfaced in the error body)
     */
    public InvalidQuoteSignatureException(String message) {
        super(message, "INVALID_QUOTE_SIGNATURE", 403);
    }
}
