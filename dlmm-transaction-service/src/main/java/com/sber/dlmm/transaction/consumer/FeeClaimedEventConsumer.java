package com.sber.dlmm.transaction.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sber.dlmm.common.enums.TransactionType;
import com.sber.dlmm.transaction.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Bridges fee-claim events to the transactions ledger.
 *
 * <p>Before this consumer, {@code FeeService.claimFees} credited the user's
 * balance and published a {@code FeeClaimedEvent} to the {@code fee-events}
 * topic, but nobody persisted a transaction row — so a fee claim never showed
 * up under {@code /transactions/me} ("Транзакции"). This listener records each
 * settled claim as a {@link TransactionType#CLAIM_FEE} row.
 *
 * <p>FeeService publishes the BARE {@code FeeClaimedEvent} JSON (a direct
 * {@code kafkaTemplate.send}, not the outbox envelope), so we recognise it by
 * its fields rather than an {@code eventType}. Idempotent: one row per
 * {@code (positionId, claimedAt)} via a synthesised {@code idempotencyKey} and
 * the transactions table's UNIQUE index on it — a re-delivered Kafka message
 * lands on the existing row instead of duplicating. All exceptions are logged,
 * never rethrown, so one malformed payload can't wedge the partition.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FeeClaimedEventConsumer {

    private final TransactionService transactionService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "fee-events", groupId = "dlmm-transaction-service-fees")
    @Transactional
    public void onFeeEvent(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            // Tolerate both an outbox envelope ({eventType, payload}) and the
            // bare FeeClaimedEvent that FeeService actually publishes.
            String eventType = root.path("eventType").asText(null);
            JsonNode payload = root.has("payload") ? root.get("payload") : root;
            if (eventType != null && !"FeeClaimed".equals(eventType)) return;
            if (!payload.has("positionId")
                    || (!payload.has("claimedX") && !payload.has("claimedY"))) return;

            long claimedX = payload.path("claimedX").asLong(0);
            long claimedY = payload.path("claimedY").asLong(0);
            if (claimedX <= 0 && claimedY <= 0) return; // nothing was settled

            UUID positionId = UUID.fromString(payload.get("positionId").asText());
            UUID userId = UUID.fromString(payload.get("userId").asText());
            UUID poolId = payload.has("poolId") && !payload.get("poolId").isNull()
                    ? UUID.fromString(payload.get("poolId").asText()) : null;
            UUID tokenXId = payload.has("tokenXId") && !payload.get("tokenXId").isNull()
                    ? UUID.fromString(payload.get("tokenXId").asText()) : null;
            UUID tokenYId = payload.has("tokenYId") && !payload.get("tokenYId").isNull()
                    ? UUID.fromString(payload.get("tokenYId").asText()) : null;
            String claimedAt = payload.path("claimedAt").asText("");

            // One ledger row per claim settlement; dedups Kafka redeliveries.
            String idempotencyKey = "feeclaim-" + positionId + "-" + claimedAt;
            if (transactionService.findByIdempotencyKey(idempotencyKey).isPresent()) {
                log.debug("Fee-claim tx already persisted, skipping: {}", idempotencyKey);
                return;
            }

            // A claim only CREDITS the user — there is no debited "in" leg. We
            // map the two credited sides onto the row (X→in-slot, Y→out-slot)
            // purely so both amounts surface in the ledger; CLAIM_FEE marks it
            // as a reward, not a swap. A quote-only claim has everything in Y.
            // fee = 0 (claiming is free).
            try {
                var persisted = transactionService.createTransaction(
                        TransactionType.CLAIM_FEE,
                        userId, poolId,
                        claimedX > 0 ? tokenXId : null, claimedX > 0 ? claimedX : null,
                        claimedY > 0 ? tokenYId : null, claimedY > 0 ? claimedY : null,
                        0L, BigDecimal.ZERO, 0,
                        idempotencyKey, null, null);
                transactionService.confirm(persisted.getId());
                log.info("Persisted fee-claim from event: txId={} position={} user={} X={} Y={}",
                        persisted.getId(), positionId, userId, claimedX, claimedY);
            } catch (DataIntegrityViolationException dup) {
                log.debug("Fee-claim tx already persisted (unique violation): {}", idempotencyKey);
            }
        } catch (Exception e) {
            log.warn("Failed to persist fee-claim from fee-events: {}", e.getMessage(), e);
        }
    }
}
