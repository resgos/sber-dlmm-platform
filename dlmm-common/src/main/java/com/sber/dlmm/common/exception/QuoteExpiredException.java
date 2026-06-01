package com.sber.dlmm.common.exception;

/**
 * Thrown when a client tries to {@code POST /swap/execute} with a
 * {@code quoteId} whose TTL has already elapsed (default 30s).
 *
 * <p>Pinned by {@code SwapIdempotencyTest#rejectsStaleQuote} (Batch G-02).
 * Quote freshness is part of the swap-execute contract: stale quotes
 * could be reused after a long pause to lock in a price that no longer
 * reflects pool state, which is both a UX and a fairness problem.
 *
 * <p>Rendered by {@link GlobalExceptionHandler} as <b>HTTP 410 Gone</b> with error code
 * {@code "QUOTE_EXPIRED"} — the quote resource is no longer available and the client must request a
 * fresh quote rather than retry.
 */
public class QuoteExpiredException extends DlmmException {
    /**
     * @param message human-readable detail (typically the expired quoteId or its TTL) surfaced in the error body
     */
    public QuoteExpiredException(String message) {
        super(message, "QUOTE_EXPIRED", 410);
    }
}
