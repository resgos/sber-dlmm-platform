package com.sber.dlmm.oracle.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sber.dlmm.oracle.service.OhlcvAggregator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Sprint 9-DS-r4 (P1-11) — bridge from pool-engine swap events to
 * the per-pool OHLCV store.
 *
 * <p>Parses the same {@code SwapExecutedEvent} envelope that
 * transaction-service consumes (see
 * {@code dlmm-transaction-service/SwapEventConsumer}), pulls
 * {@code poolId}, {@code executionPrice}, {@code amountIn} and the
 * event timestamp, then hands them to {@link OhlcvAggregator}.
 *
 * <p>Group id is distinct from transaction-service's
 * ({@code dlmm-price-oracle-ohlcv}) so the two services consume the
 * same partition independently — Kafka tracks offsets per group.
 *
 * <p>Swallows parse failures with WARN log rather than failing the
 * poll loop; a single malformed payload should not block the whole
 * partition for the chart store.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SwapEventOhlcvConsumer {

    private final ObjectMapper objectMapper;
    private final OhlcvAggregator aggregator;

    /**
     * Consumes one {@code pool-events} message and feeds a swap tick into the
     * OHLCV aggregator.
     *
     * <p>WHAT: tolerates both envelope shapes ({@code {eventType, payload}} and
     * a bare payload), ignores anything that isn't a {@code SwapExecuted}
     * event, then extracts {@code poolId}, an execution {@code price} (falling
     * back to {@code amountOut / amountIn} when {@code executionPrice} is
     * absent), {@code amountIn} and the event time, and calls
     * {@link OhlcvAggregator#record}.
     *
     * <p>WHY swallow all parse errors with a WARN: a single malformed or
     * unexpected payload must not wedge the Kafka poll loop and block every
     * subsequent message on the partition — the chart store prefers a dropped
     * tick over a stalled consumer.
     *
     * @param message raw JSON payload delivered from the {@code pool-events}
     *                topic
     */
    @KafkaListener(topics = "pool-events", groupId = "dlmm-price-oracle-ohlcv")
    public void onPoolEvent(String message) {
        try {
            // ObjectMapper.readTree throws checked JsonProcessingException;
            // caught below alongside parse-time RuntimeExceptions so the
            // poll loop never wedges on a malformed payload.
            JsonNode root;
            try {
                root = objectMapper.readTree(message);
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                log.warn("Skipping malformed pool-events payload: {}", ex.toString());
                return;
            }
            // Same dual-shape envelope handling as transaction-service:
            // some producers wrap in {eventType, payload}, others ship
            // bare payload.
            String eventType = root.path("eventType").asText(null);
            JsonNode payload = root.has("payload") ? root.get("payload") : root;
            if (eventType == null) {
                // Bare-payload heuristic.
                if (!payload.has("poolId") || !payload.has("executionPrice")) return;
                eventType = "SwapExecuted";
            }
            if (!"SwapExecuted".equals(eventType)) return;

            UUID poolId = UUID.fromString(payload.get("poolId").asText());
            BigDecimal price;
            if (payload.has("executionPrice") && !payload.get("executionPrice").isNull()) {
                price = new BigDecimal(payload.get("executionPrice").asText());
            } else if (payload.has("amountIn") && payload.has("amountOut")
                    && payload.get("amountIn").asLong() > 0) {
                // Fallback: derive price = amountOut / amountIn. This
                // matches the X→Y direction; for Y→X swaps the result
                // is the reciprocal, but for the chart we want a
                // consistent Y-per-X frame anyway, so callers should
                // populate executionPrice properly. See
                // SwapService.execute (Sprint 9-DS-r3 fix).
                price = new BigDecimal(payload.get("amountOut").asLong())
                        .divide(new BigDecimal(payload.get("amountIn").asLong()), 18, java.math.RoundingMode.HALF_UP);
            } else {
                return; // not enough data to record
            }

            long amountIn = payload.path("amountIn").asLong(0);
            long swapEpochSec = parseEventTimestamp(payload);

            aggregator.record(poolId, swapEpochSec, price, amountIn);
        } catch (RuntimeException ex) {
            log.warn("Failed to record OHLCV tick from pool-events: {}", ex.toString());
        }
    }

    /**
     * Tries the event's own timestamp first ({@code timestamp},
     * {@code createdAt}); falls back to current wall clock so a
     * payload without a timestamp still contributes a candle (just
     * in the wrong minute if there's clock skew between producer
     * and consumer).
     *
     * <p>Accepts either a numeric epoch (auto-detecting millis vs seconds by
     * magnitude) or an ISO-8601 {@link LocalDateTime} string, trying the
     * candidate fields in order.
     *
     * @param payload the swap event payload to read the timestamp from
     * @return the event time in epoch seconds, or the current wall-clock time
     *         (epoch seconds) if no usable timestamp field is present
     */
    private static long parseEventTimestamp(JsonNode payload) {
        for (String field : new String[]{"timestamp", "createdAt", "executedAt"}) {
            JsonNode n = payload.path(field);
            if (n.isMissingNode() || n.isNull()) continue;
            // Long epoch millis?
            if (n.isNumber()) {
                long v = n.asLong();
                return v > 1_000_000_000_000L ? v / 1000L : v; // detect ms vs s
            }
            // ISO-8601 string?
            String s = n.asText();
            try {
                return LocalDateTime.parse(s).toEpochSecond(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        return System.currentTimeMillis() / 1000L;
    }
}
