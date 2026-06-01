package com.sber.dlmm.pool.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sber.dlmm.pool.dto.QuotedSwap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis-backed default impl of {@link QuoteStore}. Quotes live in Redis
 * with TTL eviction so we don't have to chase down stale entries.
 *
 * <p>Key format: {@code swap:quote:<quoteId>}. Value is the JSON-encoded
 * {@link QuotedSwap}. TTL is configured via {@code dlmm.swap.quote-ttl-seconds}
 * (default 30s). Double-execute guard uses a CAS-style update via Redis
 * SETNX on a sibling key {@code swap:quote:executed:<quoteId>} — first
 * caller wins, second sees the marker and is rejected.
 *
 * <p>The double-execute flag is a *separate* key (not a re-write of the
 * quote JSON) so the check is a single-key SETNX, not a read-modify-write
 * that needs Redis transactions. The quote JSON itself stays read-only
 * once stored.
 */
@Component
public class RedisQuoteStore implements QuoteStore {

    private static final Logger log = LoggerFactory.getLogger(RedisQuoteStore.class);
    private static final String QUOTE_PREFIX = "swap:quote:";
    private static final String EXECUTED_PREFIX = "swap:quote:executed:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final long ttlSeconds;

    /**
     * @param redis      Redis template used for the quote JSON + executed marker
     * @param mapper     Jackson mapper serialising/deserialising {@link QuotedSwap}
     * @param ttlSeconds quote TTL in seconds (a small grace is added on top so an
     *                   edge-of-window execute reports "expired" not "not found")
     */
    public RedisQuoteStore(StringRedisTemplate redis,
                           ObjectMapper mapper,
                           @Value("${dlmm.swap.quote-ttl-seconds:30}") long ttlSeconds) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * Serialise the quote to JSON and store it under {@code swap:quote:<id>} with
     * TTL = configured TTL + 5s grace.
     *
     * @param quote the quote to persist
     * @throws IllegalStateException if the quote cannot be serialised
     */
    @Override
    public void save(QuotedSwap quote) {
        String key = QUOTE_PREFIX + quote.quoteId();
        try {
            String json = mapper.writeValueAsString(quote);
            // TTL = quote-ttl-seconds + a small grace so a quote at the
            // edge of the window can still emit the right "expired" error
            // instead of looking like it never existed. Without the grace,
            // execute-at-TTL=30 vs Redis-evict-at-30 race could surface as
            // "QUOTE_NOT_FOUND" instead of "QUOTE_EXPIRED".
            Duration totalTtl = Duration.ofSeconds(ttlSeconds + 5);
            redis.opsForValue().set(key, json, totalTtl);
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialise QuotedSwap {}", quote.quoteId(), ex);
            throw new IllegalStateException("Failed to persist quote " + quote.quoteId(), ex);
        }
    }

    /**
     * Read the quote JSON and layer the (separate) executed-marker key back onto
     * the returned record, so callers see a consistent {@code executedAt}
     * without knowing about the sibling key.
     *
     * @param quoteId id of the quote to fetch
     * @return the quote, or empty if missing/evicted or if the JSON can't be parsed
     */
    @Override
    public Optional<QuotedSwap> findById(UUID quoteId) {
        String key = QUOTE_PREFIX + quoteId;
        String json = redis.opsForValue().get(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            QuotedSwap stored = mapper.readValue(json, QuotedSwap.class);
            // Layer the "executed" flag back onto the returned record so
            // callers see a consistent view without having to know about
            // the sibling key. executedAt comes from the separate marker
            // key, which is the source of truth for double-execute.
            String executedAt = redis.opsForValue().get(EXECUTED_PREFIX + quoteId);
            if (executedAt != null && stored.executedAt() == null) {
                return Optional.of(stored.markExecuted(Instant.parse(executedAt)));
            }
            return Optional.of(stored);
        } catch (JsonProcessingException ex) {
            log.error("Failed to deserialise QuotedSwap {}", quoteId, ex);
            return Optional.empty();
        }
    }

    /**
     * SETNX the executed-marker key {@code swap:quote:executed:<id>}: the first
     * caller writes and wins, later callers see the marker and get {@code false}
     * — the atomic hook the double-execute guard relies on. The marker carries a
     * generous 24h TTL so a late retry still reads "already executed".
     *
     * @param quoteId id of the quote to mark executed
     * @return {@code true} if this call set the marker, {@code false} if present
     */
    @Override
    public boolean markExecuted(UUID quoteId) {
        String key = EXECUTED_PREFIX + quoteId;
        String now = Instant.now().toString();
        // SETNX-style: first call writes, subsequent calls see the marker
        // and get FALSE back. TTL on the marker is the quote TTL plus a
        // generous grace so a late retry well after expiry still gets
        // "already executed" rather than "expired" (which would be a
        // misleading error for the client).
        Boolean wasAbsent = redis.opsForValue()
                .setIfAbsent(key, now, Duration.ofHours(24));
        return Boolean.TRUE.equals(wasAbsent);
    }

    /**
     * Visible to {@link SwapService} so it can stamp createdAt + TTL math
     * consistently.
     *
     * @return the configured quote TTL in seconds (without the storage grace)
     */
    public long getTtlSeconds() {
        return ttlSeconds;
    }
}
