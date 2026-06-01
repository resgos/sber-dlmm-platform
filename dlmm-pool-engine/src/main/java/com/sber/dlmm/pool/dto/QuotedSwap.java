package com.sber.dlmm.pool.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Server-side record of a swap quote, used by the quote-execute
 * idempotency layer (Batch G-02).
 *
 * <p>When a client calls {@code POST /swap/quote}, the server returns a
 * {@code quoteId} (UUID) and stashes one of these records in {@link
 * com.sber.dlmm.pool.service.QuoteStore} with the configured TTL. When
 * the client follows up with {@code POST /swap/execute}, the server
 * looks up the record by {@code quoteId} and validates:
 * <ol>
 *   <li>not stale: {@code Instant.now() <= createdAt + TTL}</li>
 *   <li>not double-spent: {@code executedAt == null}</li>
 *   <li>signature matches: {@code req.signature().equals(signature)}</li>
 * </ol>
 *
 * <p>{@code signature} is an opaque token bound to the requesting user
 * (currently derived from their JWT subject; in OTC desk flow it's an
 * HMAC over the quoted parameters). The test suite treats it as opaque.
 *
 * <p>{@code executedAt} flips from {@code null} to the consumption
 * timestamp on first successful execute. A second execute call with
 * the same {@code quoteId} sees a non-null {@code executedAt} and is
 * rejected with {@link com.sber.dlmm.common.exception.QuoteAlreadyExecutedException}.
 *
 * @param quoteId            id of this quote; the handle the client passes to execute
 * @param userId             user the quote was issued to; execution is bound to them
 * @param poolId             pool the quote is for
 * @param tokenInId          token to be sold
 * @param tokenOutId         token to be received
 * @param amountIn           quoted input amount, raw integer at 10⁻⁴ scale
 * @param estimatedAmountOut quoted output amount, raw integer at 10⁻⁴ scale
 * @param estimatedFee       quoted fee, raw integer at 10⁻⁴ scale, in the input token
 * @param signature          opaque token bound to the user/quote; re-presented and
 *                           checked on execute (see class doc)
 * @param createdAt          when the quote was minted; TTL is measured from here
 * @param executedAt         when the quote was consumed, or {@code null} while it is
 *                           still unused (the double-spend guard)
 */
public record QuotedSwap(
        UUID quoteId,
        UUID userId,
        UUID poolId,
        UUID tokenInId,
        UUID tokenOutId,
        long amountIn,
        long estimatedAmountOut,
        long estimatedFee,
        String signature,
        Instant createdAt,
        Instant executedAt
) {

    /** Convenience: copy with {@code executedAt} populated. */
    public QuotedSwap markExecuted(Instant when) {
        return new QuotedSwap(quoteId, userId, poolId, tokenInId, tokenOutId,
                amountIn, estimatedAmountOut, estimatedFee, signature, createdAt, when);
    }
}
