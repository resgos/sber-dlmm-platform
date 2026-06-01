package com.sber.dlmm.pool.service;

import com.sber.dlmm.pool.dto.QuotedSwap;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for swap quotes consumed by the quote-execute
 * idempotency layer (Batch G-02).
 *
 * <p>Production implementation backs onto Redis with TTL eviction;
 * tests stub this directly so the time-sensitive paths (stale quote,
 * double-execute, signature mismatch) don't need a real Redis or
 * wall-clock waits. Anything that touches Redis lives behind this
 * interface for that reason — the production class is the one place
 * we string Jackson + StringRedisTemplate together.
 */
public interface QuoteStore {

    /**
     * Persist a fresh quote with the configured TTL.
     *
     * @param quote the quote to store, keyed by its {@code quoteId}
     */
    void save(QuotedSwap quote);

    /**
     * Look up a quote by id; empty when expired (Redis evicted it) or never issued.
     *
     * @param quoteId id of the quote to fetch
     * @return the quote (with any persisted executed-marker applied), or empty
     */
    Optional<QuotedSwap> findById(UUID quoteId);

    /**
     * Atomically flip {@code executedAt} from {@code null} to {@code now}.
     * Returns {@code true} on first execute, {@code false} when the quote
     * was already consumed (this is the double-execute guard's hook).
     * Implementations must serialize concurrent calls — Redis WATCH/MULTI
     * or a similar SET-IF-MATCH is fine.
     *
     * @param quoteId id of the quote to mark executed
     * @return {@code true} if this call won the marker (first execute),
     *         {@code false} if it was already marked
     */
    boolean markExecuted(UUID quoteId);
}
