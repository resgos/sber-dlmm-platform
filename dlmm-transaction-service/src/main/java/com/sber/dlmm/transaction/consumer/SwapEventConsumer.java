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
 * Sprint 9-DS-r2 — bridge between pool-engine swap mutations and the
 * transactions table.
 *
 * <p>Before this consumer existed, every live swap mutated balances and
 * pool state but never persisted a row in the transactions table — the
 * SwapExecuted event was emitted to the {@code pool-events} topic but
 * nobody consumed it. Effect: /transactions/me silently stayed at the
 * seed-history rows from 2026-05-16, and the user-ui "Открытые хеджи"
 * tab (which filters {@code idempotencyKey.startsWith("hedge-")}) was
 * always empty even after a successful hedge.
 *
 * <p>This listener:
 *  - subscribes to {@code pool-events}
 *  - filters for SwapExecuted (the outbox envelope's {@code eventType})
 *  - calls {@link TransactionService#createTransaction} with the
 *    direction + amounts + idempotencyKey
 *  - immediately confirms the resulting row
 *
 * <p>Idempotent at the persistence layer: the transactions table has a
 * unique index on {@code idempotency_key}, so a re-delivered Kafka
 * message lands on the existing row instead of duplicating.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SwapEventConsumer {

    private final TransactionService transactionService;
    private final ObjectMapper objectMapper;

    /**
     * The outbox dispatcher in pool-engine wraps the payload like:
     *   { "eventType": "SwapExecuted",
     *     "aggregateType": "pool",
     *     "aggregateId": "<poolId>",
     *     "payload": { ...SwapExecutedEvent fields... } }
     *
     * We parse the envelope, then the payload. SwapExecutedEvent is in
     * dlmm-pool-engine package — no compile-time dependency from
     * transaction-service, so we map by field name with Jackson.
     *
     * <p>Non-SwapExecuted messages are ignored. Already-persisted swaps are
     * skipped via the dual dedup keys (idempotencyKey + poolEngineTxId); a lost
     * race on either UNIQUE constraint is caught as a duplicate. The method
     * deliberately swallows all exceptions (logs, never rethrows) so a single
     * malformed message can't become a poison pill that stalls the partition.
     *
     * @param message the raw JSON string delivered from the {@code pool-events}
     *                topic (outbox envelope, or a bare payload)
     */
    @KafkaListener(topics = "pool-events", groupId = "dlmm-transaction-service-swaps")
    @Transactional
    public void onPoolEvent(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            // The outbox envelope may either be flat (just the payload)
            // or wrapped — handle both shapes defensively.
            String eventType = root.path("eventType").asText(null);
            JsonNode payload = root.has("payload") ? root.get("payload") : root;
            if (eventType == null) {
                // Bare payload — recognise SwapExecuted by the presence
                // of `tokenInId` + `amountOut`.
                if (!payload.has("tokenInId") || !payload.has("amountOut")) return;
                eventType = "SwapExecuted";
            }
            if (!"SwapExecuted".equals(eventType)) return;

            String idempotencyKey = payload.path("idempotencyKey").isMissingNode()
                    || payload.path("idempotencyKey").isNull()
                    ? null
                    : payload.path("idempotencyKey").asText();

            UUID txId = payload.has("txId") && !payload.get("txId").isNull()
                    ? UUID.fromString(payload.get("txId").asText())
                    : null;

            // Sprint 9-DS-r4 (P0-4) — dedup on BOTH keys:
            //   (a) idempotencyKey — set by the originating user/client
            //       only. Hedges, programmatic flows and re-broadcasts
            //       all use it.
            //   (b) poolEngineTxId — the swap row's own UUID from
            //       pool-engine. Always present in the SwapExecuted
            //       envelope, including for swaps that had no
            //       client-supplied idempotencyKey.
            // The application-level check is the fast path. The
            // poolEngineTxId column also carries a UNIQUE constraint,
            // so even if two consumer instances race past this check
            // the second INSERT trips a DataIntegrityViolationException
            // and we treat it as a duplicate.
            if (idempotencyKey != null
                    && transactionService.findByIdempotencyKey(idempotencyKey).isPresent()) {
                log.debug("Swap tx already persisted, skipping: idempotencyKey={}", idempotencyKey);
                return;
            }
            if (txId != null
                    && transactionService.findByPoolEngineTxId(txId).isPresent()) {
                log.debug("Swap tx already persisted, skipping: poolEngineTxId={}", txId);
                return;
            }

            UUID poolId = UUID.fromString(payload.get("poolId").asText());
            UUID userId = UUID.fromString(payload.get("userId").asText());
            UUID tokenInId = UUID.fromString(payload.get("tokenInId").asText());
            UUID tokenOutId = payload.has("tokenOutId") && !payload.get("tokenOutId").isNull()
                    ? UUID.fromString(payload.get("tokenOutId").asText())
                    : null;
            long amountIn = payload.get("amountIn").asLong();
            long amountOut = payload.get("amountOut").asLong();
            long fee = payload.path("fee").asLong(0);
            int binsCrossed = payload.path("binsCrossed").asInt(0);
            // Effective fee rate in BASIS POINTS (fee/amountIn × 10000). The
            // fee_rate column is numeric(38,2); a raw ratio (≈0.001–0.003)
            // rounded to 0.00 there, so EVERY swap recorded fee_rate=0. bps is
            // the platform's fee unit (baseFeeBps/estimatedFeeBps) and fits
            // scale-2 cleanly (e.g. a 0.30% fee → 30.00 bps).
            BigDecimal feeRate = amountIn > 0
                    ? BigDecimal.valueOf(fee).multiply(BigDecimal.valueOf(10_000L))
                        .divide(BigDecimal.valueOf(amountIn), 2, java.math.RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            try {
                var persisted = transactionService.createTransaction(
                        TransactionType.SWAP,
                        userId, poolId,
                        tokenInId, amountIn,
                        tokenOutId, amountOut,
                        fee, feeRate, binsCrossed,
                        idempotencyKey,
                        txId,
                        /* metadata */ null);

                // Swap on the pool-engine side is atomic — by the time
                // we see the event the funds have already moved. Mark
                // CONFIRMED immediately so it shows up correctly in
                // the user feed.
                transactionService.confirm(persisted.getId());

                log.info("Persisted swap from event: txId={} poolEngineTxId={} user={} pool={} key={}",
                        persisted.getId(), txId, userId, poolId, idempotencyKey);
            } catch (DataIntegrityViolationException dup) {
                // Race: another consumer instance (or this one on a
                // re-balance) got there first. The UNIQUE constraints
                // on idempotency_key + pool_engine_tx_id make this
                // safe — we just acknowledge.
                log.debug("Swap tx already persisted (DB unique violation), skipping: " +
                        "poolEngineTxId={} key={}", txId, idempotencyKey);
            }
        } catch (Exception e) {
            // We log but don't rethrow — the alternative is a poison
            // pill that blocks the partition. The outbox dispatcher
            // will not retry on its own; operator can replay via Kafka
            // consumer offset reset if needed.
            log.warn("Failed to persist swap from pool-events: {}", e.getMessage(), e);
        }
    }
}
