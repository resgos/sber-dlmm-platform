package com.sber.dlmm.common.exception;

/**
 * Thrown when a client re-submits {@code POST /swap/execute} with a
 * {@code quoteId} that has already been consumed. Each quote is single-
 * use; the same quoteId cannot drive two balance mutations.
 *
 * <p>Pinned by {@code SwapIdempotencyTest#rejectsDoubleExecute} (Batch
 * G-02). This is the stronger guarantee on top of Redis-key idempotency
 * (which depends on the client supplying the same key) — the server
 * tracks executedAt on the quote itself, so a network-retried execute
 * call with the same quoteId is rejected regardless of any client-side
 * key.
 *
 * <p>Rendered by {@link GlobalExceptionHandler} as <b>HTTP 409 Conflict</b> with error code
 * {@code "QUOTE_ALREADY_EXECUTED"}.
 */
public class QuoteAlreadyExecutedException extends DlmmException {
    /**
     * @param message human-readable detail (typically the already-consumed quoteId) surfaced in the error body
     */
    public QuoteAlreadyExecutedException(String message) {
        super(message, "QUOTE_ALREADY_EXECUTED", 409);
    }
}
