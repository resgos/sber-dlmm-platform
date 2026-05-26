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
 */
public class QuoteAlreadyExecutedException extends DlmmException {
    public QuoteAlreadyExecutedException(String message) {
        super(message, "QUOTE_ALREADY_EXECUTED", 409);
    }
}
