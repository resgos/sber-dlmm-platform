package com.sber.dlmm.oracle.service;

import com.sber.dlmm.oracle.dto.OhlcvCandleResponse;
import com.sber.dlmm.oracle.entity.OhlcvCandle;
import com.sber.dlmm.oracle.repository.OhlcvCandleRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        if (intervalSec != OhlcvAggregator.CANDLE_INTERVAL_SEC) {
            return Collections.emptyList();
        }
        int clamped = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<OhlcvCandle> recent = repository.findRecent(poolId, intervalSec, PageRequest.of(0, clamped));
        // findRecent is DESC; reverse to ASC so the chart paints
        // left→right chronologically.
        Collections.reverse(recent);
        return recent.stream()
                .map(c -> new OhlcvCandleResponse(
                        c.getOpenTime(),
                        c.getOpenPrice(),
                        c.getHighPrice(),
                        c.getLowPrice(),
                        c.getClosePrice(),
                        c.getVolumeIn(),
                        c.getSwapCount()))
                .toList();
    }
}
