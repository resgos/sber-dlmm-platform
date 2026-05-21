package com.sber.dlmm.token.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sber.dlmm.token.entity.SpasiboWritebackEntry;
import com.sber.dlmm.token.service.SpasiboWritebackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Sprint 9-DS-r4 (P1-17) — bridge from pool-engine SWAP events to
 * SberSpasibo cashback accrual.
 *
 * <p>Consumer group is distinct from
 * {@code dlmm-transaction-service-swaps} and
 * {@code dlmm-price-oracle-ohlcv} so the three services consume the
 * same partition independently — Kafka tracks offsets per group.
 *
 * <p>Why a separate consumer instead of hooking off
 * {@code SpasiboService}: the existing service handles the
 * mint/convert paths (Sprint 5 #5.3, #5.4) which are user-initiated
 * REST calls. Cashback accrual is event-driven on third-party
 * swaps, so a new consumer with its own group/lifecycle is the
 * cleaner pattern.
 *
 * <p>HEDGE / ADD_LIQUIDITY / CLAIM_FEE hooks land when their
 * respective event types are emitted on pool-events. For MVP we
 * accrue only on SWAP (highest volume; lowest design risk).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SpasiboWritebackSwapConsumer {

    private final ObjectMapper objectMapper;
    private final SpasiboWritebackService service;

    /**
     * Same envelope shape that transaction-service's SwapEventConsumer
     * + price-oracle's SwapEventOhlcvConsumer parse: optionally wrapped
     * in {@code {eventType, payload}}, otherwise bare payload.
     *
     * <p>We treat HEDGE swaps (identified by
     * {@code idempotencyKey.startsWith("hedge-")}) at the premium
     * rate per design §3. Hedges go through the same SwapExecuted
     * event as regular swaps — the idempotency key is the only
     * tell at this layer.
     */
    @KafkaListener(topics = "pool-events", groupId = "dlmm-token-service-spasibo-wb")
    public void onPoolEvent(String message) {
        try {
            JsonNode root;
            try {
                root = objectMapper.readTree(message);
            } catch (JsonProcessingException ex) {
                log.warn("Spasibo write-back: skipping malformed pool-events payload: {}", ex.toString());
                return;
            }
            String eventType = root.path("eventType").asText(null);
            JsonNode payload = root.has("payload") ? root.get("payload") : root;
            if (eventType == null) {
                if (!payload.has("userId") || !payload.has("amountIn")) return;
                eventType = "SwapExecuted";
            }
            if (!"SwapExecuted".equals(eventType)) return;

            // Required fields. If any are missing or malformed, log
            // and skip — we never want to wedge the partition over a
            // cashback accrual.
            UUID txId = payload.has("txId") && !payload.get("txId").isNull()
                    ? UUID.fromString(payload.get("txId").asText())
                    : null;
            UUID userId = payload.has("userId") && !payload.get("userId").isNull()
                    ? UUID.fromString(payload.get("userId").asText())
                    : null;
            if (txId == null || userId == null) return;

            long amountIn = payload.path("amountIn").asLong(0);
            if (amountIn <= 0) return;

            // Hedge detection per design §3. The HedgePage submits
            // swaps with `idempotencyKey = "hedge-" + uuid`; everything
            // else is a regular swap.
            String key = payload.path("idempotencyKey").asText("");
            String reasonCode = key.startsWith("hedge-")
                    ? SpasiboWritebackEntry.ReasonCodes.HEDGE
                    : SpasiboWritebackEntry.ReasonCodes.SWAP;

            service.accrue(txId, userId, reasonCode, amountIn);
        } catch (RuntimeException ex) {
            log.warn("Spasibo write-back: failed to process pool-events: {}", ex.toString());
        }
    }
}
