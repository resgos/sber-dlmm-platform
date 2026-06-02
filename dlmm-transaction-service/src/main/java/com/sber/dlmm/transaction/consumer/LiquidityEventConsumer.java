package com.sber.dlmm.transaction.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sber.dlmm.common.enums.TransactionType;
import com.sber.dlmm.transaction.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Records add/remove-liquidity events to the transactions ledger.
 *
 * <p>pool-engine emits {@code LiquidityAdded} / {@code LiquidityRemoved} to
 * {@code pool-events}, but the two payloads are byte-for-byte the same shape
 * ({@code poolId/userId/positionId/amountX/amountY}) — so a value-only consumer
 * can't tell them apart, and live LP add/remove never reached the ledger. The
 * outbox dispatcher now stamps the row's {@code event_type} onto a Kafka
 * {@code eventType} header (see {@code OutboxDispatcher}); this consumer reads
 * it to disambiguate and records an {@link TransactionType#ADD_LIQUIDITY} /
 * {@link TransactionType#REMOVE_LIQUIDITY} row.
 *
 * <p>The events carry no token ids, so we resolve the pool's X/Y tokens from
 * the shared {@code liquidity_pools} table to label the row. Idempotency:
 * {@code (op, positionId, amountX, amountY)} + the table's UNIQUE index dedups
 * Kafka redeliveries. All exceptions are logged, never rethrown.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LiquidityEventConsumer {

    private final TransactionService transactionService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    @KafkaListener(topics = "pool-events", groupId = "dlmm-transaction-service-liquidity")
    @Transactional
    public void onPoolEvent(@Payload String message,
                            @Header(name = "eventType", required = false) String eventType) {
        try {
            TransactionType type;
            if ("LiquidityAdded".equals(eventType)) {
                type = TransactionType.ADD_LIQUIDITY;
            } else if ("LiquidityRemoved".equals(eventType)) {
                type = TransactionType.REMOVE_LIQUIDITY;
            } else {
                return; // swaps / other events / pre-header messages — not ours
            }

            JsonNode payload = objectMapper.readTree(message);
            if (payload.has("payload")) payload = payload.get("payload"); // defensive

            long amountX = payload.path("amountX").asLong(0);
            long amountY = payload.path("amountY").asLong(0);
            if (amountX <= 0 && amountY <= 0) return; // nothing moved

            UUID poolId = UUID.fromString(payload.get("poolId").asText());
            UUID userId = UUID.fromString(payload.get("userId").asText());
            UUID positionId = payload.has("positionId") && !payload.get("positionId").isNull()
                    ? UUID.fromString(payload.get("positionId").asText()) : null;

            UUID[] tokens = lookupPoolTokens(poolId);

            String prefix = type == TransactionType.ADD_LIQUIDITY ? "liqadd-" : "liqrem-";
            String idempotencyKey = prefix + positionId + "-" + amountX + "-" + amountY;
            if (transactionService.findByIdempotencyKey(idempotencyKey).isPresent()) {
                log.debug("Liquidity tx already persisted, skipping: {}", idempotencyKey);
                return;
            }

            try {
                var persisted = transactionService.createTransaction(
                        type, userId, poolId,
                        amountX > 0 ? tokens[0] : null, amountX > 0 ? amountX : null,
                        amountY > 0 ? tokens[1] : null, amountY > 0 ? amountY : null,
                        0L, BigDecimal.ZERO, 0,
                        idempotencyKey, null, null);
                transactionService.confirm(persisted.getId());
                log.info("Persisted {} from event: txId={} position={} user={} X={} Y={}",
                        type, persisted.getId(), positionId, userId, amountX, amountY);
            } catch (DataIntegrityViolationException dup) {
                log.debug("Liquidity tx already persisted (unique violation): {}", idempotencyKey);
            }
        } catch (Exception e) {
            log.warn("Failed to persist liquidity tx from pool-events: {}", e.getMessage(), e);
        }
    }

    /**
     * Resolve a pool's X/Y token ids from the shared {@code liquidity_pools}
     * table (the liquidity events don't carry them). Returns {@code {null,
     * null}} if the pool can't be read — the row is still recorded, just
     * without token symbols.
     */
    private UUID[] lookupPoolTokens(UUID poolId) {
        try {
            UUID[] r = jdbcTemplate.queryForObject(
                    "SELECT token_x_id, token_y_id FROM liquidity_pools WHERE id = ?",
                    (rs, n) -> new UUID[]{
                            rs.getObject("token_x_id", UUID.class),
                            rs.getObject("token_y_id", UUID.class)
                    },
                    poolId);
            return r != null ? r : new UUID[]{null, null};
        } catch (Exception e) {
            return new UUID[]{null, null};
        }
    }
}
