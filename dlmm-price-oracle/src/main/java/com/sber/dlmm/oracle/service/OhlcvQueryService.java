package com.sber.dlmm.oracle.service;

import com.sber.dlmm.oracle.dto.OhlcvCandleResponse;
import com.sber.dlmm.oracle.entity.OhlcvCandle;
import com.sber.dlmm.oracle.repository.OhlcvCandleRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Sprint 9-DS-r4 (P1-11) — read side of the OHLCV store. The write
 * side is split out into {@link OhlcvAggregator} so the per-tick
 * Kafka path doesn't pull in service-layer dependencies.
 */
@Service
public class OhlcvQueryService {

    /**
     * Caller-supplied limit cap. lightweight-charts paints comfortably
     * up to ~1000 candles per visible viewport; 500 is the typical
     * Meteora/Trading-View window and keeps the JSON payload under
     * ~50KB.
     */
    private static final int MAX_LIMIT = 500;

    /**
     * Upper bound on 1-minute candles pulled to build a rolled-up series, so a
     * large interval × limit can't request an unbounded page. ~3000 one-minute
     * candles = ~50h, plenty for any visible higher-timeframe window in the demo.
     */
    private static final int MAX_ROLLUP_SOURCE = 3000;

    private final OhlcvCandleRepository repository;

    /**
     * Creates the query service with its read-only candle repository.
     *
     * @param repository repository used to page recent candles for a pool
     */
    public OhlcvQueryService(OhlcvCandleRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns the most recent {@code limit} candles for {@code poolId}
     * at the requested {@code intervalSec}, ordered oldest-first
     * (TradingView convention).
     *
     * <p>WHY the clamp + reverse: the repository query is DESC and paged, so we
     * cap {@code limit} to {@link #MAX_LIMIT} to bound the payload, then reverse
     * to ASC so the chart paints left→right chronologically.
     *
     * @param poolId      pool whose candles to fetch
     * @param intervalSec only 60 is supported today; higher intervals
     *                    are emitted by a future hourly roll-up job
     *                    (Sprint 10) but rejected here for now to
     *                    avoid silently serving empty arrays
     * @param limit       requested candle count; clamped to the range
     *                    {@code 1..}{@link #MAX_LIMIT}
     * @return candles oldest-first, or an empty list when the interval is
     *         unsupported or the pool has no candles
     */
    @Transactional(readOnly = true)
    public List<OhlcvCandleResponse> getCandles(UUID poolId, int intervalSec, int limit) {
        final int base = OhlcvAggregator.CANDLE_INTERVAL_SEC; // 60
        final int clamped = Math.max(1, Math.min(limit, MAX_LIMIT));

        // Base 1-minute series — serve the stored candles directly.
        if (intervalSec == base) {
            // Defensive copy: findRecent's result may be immutable; Collections.reverse mutates.
            List<OhlcvCandle> recent = new ArrayList<>(repository.findRecent(poolId, base, PageRequest.of(0, clamped)));
            Collections.reverse(recent); // DESC → ASC so the chart paints left→right
            return recent.stream().map(OhlcvQueryService::toResponse).toList();
        }

        // Higher timeframes (5m / 15m / 1h …) — roll up the 1-minute candles into
        // intervalSec buckets ON READ (no extra write-side job). Only exact
        // multiples of the 1-minute base make sense; anything else is empty.
        if (intervalSec <= 0 || intervalSec % base != 0) {
            return Collections.emptyList();
        }
        final int factor = intervalSec / base;
        final int sourceCount = Math.min(clamped * factor, MAX_ROLLUP_SOURCE);
        // Defensive copy: findRecent's result may be immutable; Collections.reverse mutates.
        List<OhlcvCandle> oneMin = new ArrayList<>(repository.findRecent(poolId, base, PageRequest.of(0, sourceCount)));
        if (oneMin.isEmpty()) {
            return Collections.emptyList();
        }
        Collections.reverse(oneMin); // ASC time

        // Aggregate consecutive 1-minute candles sharing a UTC-aligned bucket:
        // open = first, high = max, low = min, close = last, volume/swaps = sum.
        List<OhlcvCandleResponse> rolled = new ArrayList<>();
        long curBucket = Long.MIN_VALUE;
        LocalDateTime bucketStart = null;
        BigDecimal open = null, high = null, low = null, close = null;
        long volume = 0;
        long swaps = 0;
        for (OhlcvCandle c : oneMin) {
            long epoch = c.getOpenTime().toEpochSecond(ZoneOffset.UTC);
            long bucket = Math.floorDiv(epoch, intervalSec) * (long) intervalSec;
            if (bucket != curBucket) {
                if (curBucket != Long.MIN_VALUE) {
                    rolled.add(new OhlcvCandleResponse(bucketStart, open, high, low, close,
                            volume, (int) Math.min(swaps, Integer.MAX_VALUE)));
                }
                curBucket = bucket;
                bucketStart = LocalDateTime.ofEpochSecond(bucket, 0, ZoneOffset.UTC);
                open = c.getOpenPrice();
                high = c.getHighPrice();
                low = c.getLowPrice();
                close = c.getClosePrice();
                volume = c.getVolumeIn();
                swaps = c.getSwapCount();
            } else {
                high = high.max(c.getHighPrice());
                low = low.min(c.getLowPrice());
                close = c.getClosePrice(); // last 1-min close in the bucket wins
                volume += c.getVolumeIn();
                swaps += c.getSwapCount();
            }
        }
        if (curBucket != Long.MIN_VALUE) {
            rolled.add(new OhlcvCandleResponse(bucketStart, open, high, low, close,
                    volume, (int) Math.min(swaps, Integer.MAX_VALUE)));
        }
        // Keep only the most-recent `clamped` buckets, oldest-first.
        return rolled.size() > clamped ? rolled.subList(rolled.size() - clamped, rolled.size()) : rolled;
    }

    private static OhlcvCandleResponse toResponse(OhlcvCandle c) {
        return new OhlcvCandleResponse(
                c.getOpenTime(), c.getOpenPrice(), c.getHighPrice(),
                c.getLowPrice(), c.getClosePrice(), c.getVolumeIn(), c.getSwapCount());
    }
}
