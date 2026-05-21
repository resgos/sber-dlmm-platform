package com.sber.dlmm.transaction.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sber.dlmm.common.enums.TransactionStatus;
import com.sber.dlmm.common.enums.TransactionType;
import com.sber.dlmm.transaction.entity.Transaction;
import com.sber.dlmm.transaction.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 9-DS-r4 (P0-4) — pins the two dedup paths in
 * {@link SwapEventConsumer}.
 *
 * <p>Before this fix the consumer only checked {@code idempotencyKey},
 * so re-delivered events for programmatic swaps (which often had no
 * key) created duplicate rows. The contract now is:
 *  1. If idempotencyKey is set and already known → skip (no DB write).
 *  2. Else if poolEngineTxId is already known → skip (no DB write).
 *  3. Else insert + confirm.
 *
 * <p>We don't exercise the {@code DataIntegrityViolationException}
 * race path here — that's a DB-level guarantee that an integration
 * test with Testcontainers covers more honestly than a Mockito mock
 * could.
 */
class SwapEventConsumerTest {

    private TransactionService transactionService;
    private ObjectMapper objectMapper;
    private SwapEventConsumer consumer;

    @BeforeEach
    void setUp() {
        transactionService = mock(TransactionService.class);
        objectMapper = new ObjectMapper();
        consumer = new SwapEventConsumer(transactionService, objectMapper);
    }

    @Test
    void skips_when_idempotencyKey_already_persisted() {
        String key = "swap-abc-123";
        when(transactionService.findByIdempotencyKey(key))
                .thenReturn(Optional.of(new Transaction()));

        consumer.onPoolEvent(buildEnvelope(key, UUID.randomUUID()));

        verify(transactionService, never()).createTransaction(
                any(TransactionType.class), any(UUID.class), any(UUID.class),
                any(UUID.class), anyLong(), any(UUID.class), anyLong(),
                anyLong(), any(), anyInt(), any(), any(UUID.class), any());
    }

    @Test
    void skips_when_poolEngineTxId_already_persisted_and_no_key() {
        UUID poolEngineTxId = UUID.randomUUID();
        when(transactionService.findByPoolEngineTxId(poolEngineTxId))
                .thenReturn(Optional.of(new Transaction()));

        consumer.onPoolEvent(buildEnvelope(/*key*/ null, poolEngineTxId));

        verify(transactionService, never()).createTransaction(
                any(TransactionType.class), any(UUID.class), any(UUID.class),
                any(UUID.class), anyLong(), any(UUID.class), anyLong(),
                anyLong(), any(), anyInt(), any(), any(UUID.class), any());
    }

    @Test
    void persists_and_confirms_when_neither_dedup_key_matches() {
        String key = "swap-fresh-1";
        UUID poolEngineTxId = UUID.randomUUID();
        when(transactionService.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        when(transactionService.findByPoolEngineTxId(poolEngineTxId)).thenReturn(Optional.empty());

        UUID newId = UUID.randomUUID();
        Transaction persisted = new Transaction();
        persisted.setId(newId);
        persisted.setStatus(TransactionStatus.CREATED);
        when(transactionService.createTransaction(
                any(TransactionType.class), any(UUID.class), any(UUID.class),
                any(UUID.class), anyLong(), any(UUID.class), anyLong(),
                anyLong(), any(), anyInt(), any(), any(UUID.class), any()))
                .thenReturn(persisted);

        consumer.onPoolEvent(buildEnvelope(key, poolEngineTxId));

        ArgumentCaptor<UUID> captured = ArgumentCaptor.forClass(UUID.class);
        verify(transactionService).createTransaction(
                eq(TransactionType.SWAP),
                any(UUID.class), any(UUID.class),
                any(UUID.class), anyLong(), any(UUID.class), anyLong(),
                anyLong(), any(), anyInt(),
                eq(key),
                captured.capture(),
                isNull());
        org.assertj.core.api.Assertions.assertThat(captured.getValue()).isEqualTo(poolEngineTxId);
        verify(transactionService).confirm(newId);
    }

    private String buildEnvelope(String idempotencyKey, UUID poolEngineTxId) {
        UUID poolId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID tokenIn = UUID.randomUUID();
        UUID tokenOut = UUID.randomUUID();
        return "{"
                + "\"eventType\":\"SwapExecuted\","
                + "\"payload\":{"
                + "  \"txId\":\"" + poolEngineTxId + "\","
                + "  \"poolId\":\"" + poolId + "\","
                + "  \"userId\":\"" + userId + "\","
                + "  \"tokenInId\":\"" + tokenIn + "\","
                + "  \"tokenOutId\":\"" + tokenOut + "\","
                + "  \"amountIn\":1000,"
                + "  \"amountOut\":990,"
                + "  \"fee\":5,"
                + "  \"binsCrossed\":0"
                + (idempotencyKey != null ? ",\"idempotencyKey\":\"" + idempotencyKey + "\"" : "")
                + "}}";
    }
}
