package com.sber.dlmm.transaction.service;

import com.sber.dlmm.common.enums.B2BSettlementStatus;
import com.sber.dlmm.common.exception.B2BSettlementValidationException;
import com.sber.dlmm.common.exception.InsufficientBalanceException;
import com.sber.dlmm.transaction.client.TokenServiceClient;
import com.sber.dlmm.transaction.dto.B2BSettlementRequest;
import com.sber.dlmm.transaction.dto.B2BSettlementResponse;
import com.sber.dlmm.transaction.entity.B2BSettlement;
import com.sber.dlmm.transaction.repository.B2BSettlementRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class B2BSettlementServiceTest {

    @Mock
    private B2BSettlementRepository repository;
    @Mock
    private TokenServiceClient tokenClient;
    @Mock
    private PlatformTransactionManager txManager;

    private B2BSettlementService service;

    private static final UUID FROM = UUID.randomUUID();
    private static final UUID TO = UUID.randomUUID();
    private static final UUID TOKEN = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Mocked txManager — TransactionTemplate inside the service calls
        // getTransaction → commit, both no-op here so the callbacks run inline.
        // lenient() because not every test triggers a transactional path
        // (self-counterparty rejection happens BEFORE any txn).
        lenient().when(txManager.getTransaction(any()))
                .thenReturn(mock(TransactionStatus.class));
        service = new B2BSettlementService(repository, tokenClient, txManager);
    }

    private B2BSettlementRequest req(UUID counterparty, long amount, String ref) {
        return new B2BSettlementRequest(counterparty, TOKEN, amount, ref, "test-notes");
    }

    @Test
    @DisplayName("submit rejects self-counterparty before any DB or token-service call")
    void rejectsSelfCounterparty() {
        B2BSettlementRequest r = req(FROM, 100L, "ref-self");

        assertThrows(B2BSettlementValidationException.class, () -> service.submit(r, FROM));

        verify(repository, never()).findByReference(any());
        verify(repository, never()).save(any());
        verify(tokenClient, never()).deduct(any(), any(), anyLong());
        verify(tokenClient, never()).credit(any(), any(), anyLong());
    }

    @Test
    @DisplayName("submit returns existing row on duplicate reference (idempotency)")
    void idempotencyReturnsExisting() {
        B2BSettlement existing = B2BSettlement.builder()
                .id(UUID.randomUUID())
                .fromUserId(FROM).toUserId(TO).tokenId(TOKEN).amount(100L)
                .reference("ref-dup").status(B2BSettlementStatus.COMPLETED)
                .requestedBy(FROM)
                .build();
        when(repository.findByReference("ref-dup")).thenReturn(Optional.of(existing));

        B2BSettlementResponse resp = service.submit(req(TO, 100L, "ref-dup"), FROM);

        assertEquals(existing.getId(), resp.id());
        assertEquals(B2BSettlementStatus.COMPLETED, resp.status());
        // Critically — neither token-service nor a second save() was hit.
        verify(repository, never()).save(any());
        verify(tokenClient, never()).deduct(any(), any(), anyLong());
        verify(tokenClient, never()).credit(any(), any(), anyLong());
    }

    @Test
    @DisplayName("submit happy path: deduct → credit → status flips to COMPLETED")
    void happyPathCompletes() {
        UUID id = UUID.randomUUID();
        when(repository.findByReference("ref-ok")).thenReturn(Optional.empty());

        // save() is called 3 times: PENDING insert, then findById+save for the
        // status flip. We return a fresh entity on insert (id assigned) and
        // hand the same row back on findById so markCompleted can mutate it.
        B2BSettlement saved = B2BSettlement.builder()
                .id(id).fromUserId(FROM).toUserId(TO).tokenId(TOKEN).amount(100L)
                .reference("ref-ok").status(B2BSettlementStatus.PENDING)
                .requestedBy(FROM)
                .build();
        when(repository.save(any(B2BSettlement.class))).thenAnswer(inv -> {
            B2BSettlement arg = inv.getArgument(0);
            // First save (PENDING insert) — assign id; subsequent saves echo through.
            if (arg.getId() == null) {
                arg.setId(id);
            }
            return arg;
        });
        when(repository.findById(id)).thenReturn(Optional.of(saved));

        B2BSettlementResponse resp = service.submit(req(TO, 100L, "ref-ok"), FROM);

        assertNotNull(resp);
        assertEquals(B2BSettlementStatus.COMPLETED, resp.status());
        assertNotNull(resp.completedAt());
        verify(tokenClient).deduct(FROM, TOKEN, 100L);
        verify(tokenClient).credit(TO, TOKEN, 100L);
    }

    @Test
    @DisplayName("submit marks FAILED [CLEAN] when deduct rejects on insufficient balance")
    void deductRejectionMarksCleanFailed() {
        UUID id = UUID.randomUUID();
        when(repository.findByReference("ref-poor")).thenReturn(Optional.empty());
        when(repository.save(any(B2BSettlement.class))).thenAnswer(inv -> {
            B2BSettlement arg = inv.getArgument(0);
            if (arg.getId() == null) arg.setId(id);
            return arg;
        });
        B2BSettlement pending = B2BSettlement.builder()
                .id(id).fromUserId(FROM).toUserId(TO).tokenId(TOKEN).amount(100L)
                .reference("ref-poor").status(B2BSettlementStatus.PENDING)
                .requestedBy(FROM)
                .build();
        when(repository.findById(id)).thenReturn(Optional.of(pending));

        // Token-service rejects the deduct — caller balance is untouched.
        org.mockito.Mockito.doThrow(new InsufficientBalanceException("Balance 50 < requested 100"))
                .when(tokenClient).deduct(eq(FROM), eq(TOKEN), eq(100L));

        B2BSettlementResponse resp = service.submit(req(TO, 100L, "ref-poor"), FROM);

        assertEquals(B2BSettlementStatus.FAILED, resp.status());
        assertTrue(resp.errorMessage().startsWith("[CLEAN] "),
                "Clean failure must be tagged for safe-retry diagnostics, got: " + resp.errorMessage());
        // Credit must NOT be attempted if deduct failed — that would create money.
        verify(tokenClient, never()).credit(any(), any(), anyLong());
    }

    @Test
    @DisplayName("submit marks FAILED [RECONCILE] when credit fails after deduct succeeded")
    void creditFailureAfterDeductMarksReconcile() {
        UUID id = UUID.randomUUID();
        when(repository.findByReference("ref-half")).thenReturn(Optional.empty());
        when(repository.save(any(B2BSettlement.class))).thenAnswer(inv -> {
            B2BSettlement arg = inv.getArgument(0);
            if (arg.getId() == null) arg.setId(id);
            return arg;
        });
        B2BSettlement pending = B2BSettlement.builder()
                .id(id).fromUserId(FROM).toUserId(TO).tokenId(TOKEN).amount(100L)
                .reference("ref-half").status(B2BSettlementStatus.PENDING)
                .requestedBy(FROM)
                .build();
        when(repository.findById(id)).thenReturn(Optional.of(pending));

        // Deduct succeeds (no stubbing needed), credit blows up — money is now
        // floating: from-side debited, to-side NOT credited. Must be loud.
        org.mockito.Mockito.doThrow(new IllegalStateException("token-service down"))
                .when(tokenClient).credit(eq(TO), eq(TOKEN), eq(100L));

        B2BSettlementResponse resp = service.submit(req(TO, 100L, "ref-half"), FROM);

        assertEquals(B2BSettlementStatus.FAILED, resp.status());
        assertTrue(resp.errorMessage().startsWith("[RECONCILE] "),
                "Partial failure must be tagged so operators see it needs manual fix, got: "
                        + resp.errorMessage());
        verify(tokenClient).deduct(FROM, TOKEN, 100L);
        verify(tokenClient).credit(TO, TOKEN, 100L);
    }

    @Test
    @DisplayName("submit captures correct from/to/token/amount in the persisted row")
    void persistedRowHasExpectedFields() {
        UUID id = UUID.randomUUID();
        when(repository.findByReference("ref-shape")).thenReturn(Optional.empty());
        ArgumentCaptor<B2BSettlement> captor = ArgumentCaptor.forClass(B2BSettlement.class);
        when(repository.save(captor.capture())).thenAnswer(inv -> {
            B2BSettlement arg = inv.getArgument(0);
            if (arg.getId() == null) arg.setId(id);
            return arg;
        });
        B2BSettlement pending = B2BSettlement.builder()
                .id(id).fromUserId(FROM).toUserId(TO).tokenId(TOKEN).amount(250L)
                .reference("ref-shape").status(B2BSettlementStatus.PENDING)
                .requestedBy(FROM)
                .build();
        when(repository.findById(id)).thenReturn(Optional.of(pending));

        service.submit(req(TO, 250L, "ref-shape"), FROM);

        B2BSettlement inserted = captor.getAllValues().get(0); // first save = PENDING insert
        assertSame(FROM, inserted.getFromUserId());
        assertSame(TO, inserted.getToUserId());
        assertSame(TOKEN, inserted.getTokenId());
        assertEquals(250L, inserted.getAmount());
        assertEquals("ref-shape", inserted.getReference());
        assertEquals(B2BSettlementStatus.PENDING, inserted.getStatus());
        assertSame(FROM, inserted.getRequestedBy());
    }
}
