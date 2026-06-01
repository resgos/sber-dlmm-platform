package com.sber.dlmm.oracle.service;

import com.sber.dlmm.oracle.entity.OhlcvCandle;
import com.sber.dlmm.oracle.repository.OhlcvCandleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Sprint 9-DS-r4 (P1-11) — in-memory OHLCV aggregator.
 *
 * <p>Maintains one mutable bucket per (poolId, minuteFloor). Each call
 * to {@link #record} updates the corresponding bucket. A scheduled
 * {@link #flush} fires every 30s and persists every bucket whose
 * minute is fully in the past (so we never write a half-formed
 * candle that's still being mutated by an in-flight swap).
 *
 * <p>Why in-process and not a Kafka Streams windowed aggregation?
 * Streams brings a 30MB+ library + a state-store RocksDB + a topology
 * — wildly overkill for the seed-scale traffic (≈15 swaps/min/pool).
 * If volume grows past 1000 swaps/min we should reconsider.
 *
 * <p>Failure semantics: on app crash mid-bucket, the in-flight
 * minute is lost. Persisted candles are unique-constrained so the
 * Kafka consumer re-running from the last committed offset can't
 * duplicate completed candles; the worst case is a missing or
 * truncated current minute.
 *
 * <p>This class is the single mutator of the {@code buckets} map.
 * The consumer thread calls {@link #record}, the scheduler thread
 * calls {@link #flush}. Both use the same {@link ConcurrentHashMap}
 * and either:
 *   - {@link #record} computes on a key — atomic per-key update;
 *   - {@link #flush} removes keys whose bucket is sealed.
 */
@Component
public class OhlcvAggregator {

    private static final Logger log = LoggerFactory.getLogger(OhlcvAggregator.class);

    /** 1-minute candle granularity for MVP; matches what TradingView expects on a default zoom. */
    public static final int CANDLE_INTERVAL_SEC = 60;

    private final OhlcvCandleRepository repository;

    /**
     * Per-(pool, minute) bucket holder. Keyed on a composite to avoid
     * a nested map; value is mutable inside the lambdas under
     * {@link ConcurrentHashMap#compute} so updates stay atomic.
     */
    private final ConcurrentMap<BucketKey, Bucket> buckets = new ConcurrentHashMap<>();

    /**
     * Creates the aggregator with its persistence gateway.
     *
     * @param repository repository used by {@link #flush} to upsert sealed
     *                   candles into Postgres
     */
    public OhlcvAggregator(OhlcvCandleRepository repository) {
        this.repository = repository;
    }

    /**
     * Apply one swap to its bucket. Called from the Kafka consumer
     * thread. {@code price} is the swap's execution price in pool's
     * tokenY-per-tokenX terms (matches what
     * {@code SwapResponse.executionPrice} produces).
     *
     * <p>The update is atomic per (pool, minute) key via
     * {@link ConcurrentHashMap#compute}: a new bucket seeds O=H=L=C=price,
     * an existing one extends high/low, advances close, and accumulates
     * volume/count. Null or non-positive prices (and null pool ids) are
     * ignored so a malformed event can't corrupt a candle.
     *
     * @param poolId       pool the swap belongs to; ignored if {@code null}
     * @param swapEpochSec swap time in epoch seconds, floored to the minute to
     *                     pick the bucket
     * @param price        execution price (tokenY-per-tokenX); ignored if
     *                     {@code null} or {@code <= 0}
     * @param amountIn     input amount of the swap, added to the bucket's
     *                     volume
     */
    public void record(UUID poolId, long swapEpochSec, BigDecimal price, long amountIn) {
        if (poolId == null || price == null || price.signum() <= 0) return;
        long minuteStart = (swapEpochSec / CANDLE_INTERVAL_SEC) * CANDLE_INTERVAL_SEC;
        BucketKey key = new BucketKey(poolId, minuteStart);

        buckets.compute(key, (k, existing) -> {
            if (existing == null) {
                return new Bucket(price, price, price, price, amountIn, 1);
            }
            BigDecimal high = price.max(existing.high);
            BigDecimal low = price.min(existing.low);
            return new Bucket(existing.open, high, low, price,
                    existing.volumeIn + amountIn, existing.swapCount + 1);
        });
    }

    /**
     * Persist every sealed bucket (open-time strictly before the
     * current minute start). Runs every 30 seconds — a bit faster
     * than the bucket width so chart users see fresh candles within
     * ~half a minute of the swap.
     *
     * <p>The merge-on-conflict path makes this idempotent against a
     * Kafka consumer restart that re-delivers events from an earlier
     * offset.
     */
    @Scheduled(fixedDelayString = "${dlmm.oracle.ohlcv-flush-interval-ms:30000}")
    @Transactional
    public void flush() {
        long nowSec = System.currentTimeMillis() / 1000L;
        long currentMinuteStart = (nowSec / CANDLE_INTERVAL_SEC) * CANDLE_INTERVAL_SEC;

        int flushed = 0;
        // snapshot the keys before removal to avoid ConcurrentModificationException
        // (ConcurrentHashMap iterators tolerate it, but mutating via remove() inside
        // the loop is the cleaner pattern).
        for (Iterator<Map.Entry<BucketKey, Bucket>> it = buckets.entrySet().iterator(); it.hasNext();) {
            Map.Entry<BucketKey, Bucket> e = it.next();
            BucketKey key = e.getKey();
            if (key.minuteStart >= currentMinuteStart) {
                // Still hot — leave it for the next swap or the next flush.
                continue;
            }
            Bucket b = e.getValue();
            persistBucket(key, b);
            it.remove();
            flushed++;
        }
        if (flushed > 0) {
            log.debug("OHLCV flush: persisted {} candle(s)", flushed);
        }
    }

    /**
     * Test hook — verifies the in-memory state without touching the
     * scheduler. Returns a snapshot copy so callers can't mutate the
     * internal map.
     *
     * @return a shallow copy of the live (pool, minute) → bucket map
     */
    Map<BucketKey, Bucket> snapshot() {
        return new HashMap<>(buckets);
    }

    /**
     * Writes one sealed bucket to the candle table, idempotently.
     *
     * <p>WHAT: converts the bucket's minute to a UTC {@link LocalDateTime} open
     * time, then either inserts a fresh candle or merges into an existing one
     * for the same (pool, interval, openTime).
     *
     * <p>WHY merge-on-conflict: a Kafka consumer restart can replay events from
     * a committed offset before this minute sealed, so the same minute may be
     * persisted twice. Merging high/low/close/volume/count in place (rather
     * than inserting) keeps the unique constraint satisfied and the candle
     * correct under replay.
     *
     * @param key the (pool, minuteStart) identity of the bucket
     * @param b   the immutable bucket snapshot to persist
     */
    private void persistBucket(BucketKey key, Bucket b) {
        LocalDateTime openTime = LocalDateTime.ofEpochSecond(key.minuteStart, 0, ZoneOffset.UTC);
        // Idempotent on re-delivery: if an earlier flush already wrote
        // this minute (e.g. consumer restart replayed from a committed
        // offset before the bucket sealed), update its O/H/L/C in
        // place rather than tripping the unique constraint.
        repository.findByPoolIdAndIntervalSecAndOpenTime(key.poolId, CANDLE_INTERVAL_SEC, openTime)
                .ifPresentOrElse(
                        existing -> {
                            existing.setHighPrice(existing.getHighPrice().max(b.high));
                            existing.setLowPrice(existing.getLowPrice().min(b.low));
                            existing.setClosePrice(b.close);
                            existing.setVolumeIn(existing.getVolumeIn() + b.volumeIn);
                            existing.setSwapCount(existing.getSwapCount() + b.swapCount);
                            repository.save(existing);
                        },
                        () -> repository.save(OhlcvCandle.builder()
                                .poolId(key.poolId)
                                .intervalSec(CANDLE_INTERVAL_SEC)
                                .openTime(openTime)
                                .openPrice(b.open)
                                .highPrice(b.high)
                                .lowPrice(b.low)
                                .closePrice(b.close)
                                .volumeIn(b.volumeIn)
                                .swapCount(b.swapCount)
                                .build())
                );
    }

    /**
     * Composite map key — keeps the bucket index O(1) without a nested map.
     *
     * @param poolId      the pool this candle belongs to (non-null)
     * @param minuteStart epoch-second of the minute floor that this candle
     *                    covers
     */
    record BucketKey(UUID poolId, long minuteStart) {
        /**
         * Compact constructor enforcing the non-null pool invariant.
         *
         * <p>WHY: the key is used in a hash map and a null pool would both NPE
         * on hashing and silently merge candles across pools — fail fast at
         * construction instead.
         *
         * @throws NullPointerException if {@code poolId} is {@code null}
         */
        public BucketKey {
            Objects.requireNonNull(poolId);
        }
    }

    /**
     * Immutable bucket value. Replaced atomically inside compute() on each tick.
     *
     * @param open      first trade price in the minute
     * @param high      highest trade price seen so far
     * @param low       lowest trade price seen so far
     * @param close     most recent trade price
     * @param volumeIn  accumulated input volume over the minute
     * @param swapCount number of swaps folded into this candle
     */
    record Bucket(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                  long volumeIn, int swapCount) {}
}
