package com.sber.dlmm.token.service;

import com.sber.dlmm.token.entity.SpasiboOperation;
import com.sber.dlmm.token.repository.SpasiboOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpasiboServiceTest {

    @Mock
    private SpasiboOperationRepository operationRepository;
    @Mock
    private TokenService tokenService;

    @InjectMocks
    private SpasiboService spasiboService;

    private static final UUID USER = UUID.randomUUID();
    private static final UUID SSPAS_ID = UUID.fromString("b0000000-0000-0000-0000-000000000201");
    private static final UUID SRUB_ID = UUID.fromString("b0000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        // @Value fields aren't populated by Mockito — set manually so the
        // service uses production defaults (matches 04-spasibo-seed.sql IDs
        // and the 1:1 conversion rate).
        ReflectionTestUtils.setField(spasiboService, "spasiboTokenId", SSPAS_ID);
        ReflectionTestUtils.setField(spasiboService, "rubTokenId", SRUB_ID);
        ReflectionTestUtils.setField(spasiboService, "conversionRate", 1L);
        // Mimic JPA @PrePersist — set id when save() is called.
        org.mockito.Mockito.lenient()
                .when(operationRepository.save(any(SpasiboOperation.class)))
                .thenAnswer(inv -> {
                    SpasiboOperation op = inv.getArgument(0);
                    if (op.getId() == null) op.setId(UUID.randomUUID());
                    if (op.getStatus() == null) op.setStatus(SpasiboOperation.Status.COMPLETED);
                    return op;
                });
    }

    // ── handleMintWebhook ──

    @Test
    @DisplayName("MINT happy path credits SSPAS + persists operation row")
    void mintCreditsAndPersists() {
        when(operationRepository.findByReference("spasibo-evt-001")).thenReturn(Optional.empty());

        SpasiboOperation op = spasiboService.handleMintWebhook(USER, 500L, "spasibo-evt-001");

        assertNotNull(op.getId());
        assertEquals(SpasiboOperation.OpType.MINT, op.getOpType());
        assertEquals(500L, op.getPoints());
        assertNull(op.getRubAmount());
        verify(tokenService).creditInternal(USER, SSPAS_ID, 500L);
    }

    @Test
    @DisplayName("MINT idempotency: duplicate reference returns existing op, no double-credit")
    void mintIdempotencyReturnsExisting() {
        SpasiboOperation existing = SpasiboOperation.builder()
                .id(UUID.randomUUID())
                .opType(SpasiboOperation.OpType.MINT)
                .userId(USER).points(500L)
                .reference("spasibo-evt-002")
                .status(SpasiboOperation.Status.COMPLETED)
                .build();
        when(operationRepository.findByReference("spasibo-evt-002")).thenReturn(Optional.of(existing));

        SpasiboOperation result = spasiboService.handleMintWebhook(USER, 500L, "spasibo-evt-002");

        assertSame(existing, result);
        verify(tokenService, never()).creditInternal(any(), any(), anyLong());
        verify(operationRepository, never()).save(any());
    }

    @Test
    @DisplayName("MINT rejects non-positive points")
    void mintRejectsNonPositive() {
        assertThrows(IllegalArgumentException.class,
                () -> spasiboService.handleMintWebhook(USER, 0L, "ref"));
        assertThrows(IllegalArgumentException.class,
                () -> spasiboService.handleMintWebhook(USER, -10L, "ref"));
    }

    @Test
    @DisplayName("MINT rejects blank reference (idempotency would be broken)")
    void mintRejectsBlankReference() {
        assertThrows(IllegalArgumentException.class,
                () -> spasiboService.handleMintWebhook(USER, 100L, ""));
        assertThrows(IllegalArgumentException.class,
                () -> spasiboService.handleMintWebhook(USER, 100L, null));
    }

    // ── convertToRub ──

    @Test
    @DisplayName("CONVERT happy path: burn SSPAS, credit SRUB, persist op")
    void convertBurnsAndCredits() {
        when(operationRepository.findByReference("convert-001")).thenReturn(Optional.empty());

        SpasiboOperation op = spasiboService.convertToRub(USER, 250L, "convert-001");

        assertEquals(SpasiboOperation.OpType.CONVERT, op.getOpType());
        assertEquals(250L, op.getPoints());
        assertEquals(250L, op.getRubAmount());
        verify(tokenService).deductInternal(USER, SSPAS_ID, 250L);
        verify(tokenService).creditInternal(USER, SRUB_ID, 250L);
    }

    @Test
    @DisplayName("CONVERT applies conversion rate (rubAmount = points × rate)")
    void convertAppliesRate() {
        ReflectionTestUtils.setField(spasiboService, "conversionRate", 3L);
        when(operationRepository.findByReference(any())).thenReturn(Optional.empty());

        SpasiboOperation op = spasiboService.convertToRub(USER, 100L, "convert-rate-3");

        assertEquals(100L, op.getPoints());
        assertEquals(300L, op.getRubAmount());
        verify(tokenService).deductInternal(USER, SSPAS_ID, 100L);
        verify(tokenService).creditInternal(USER, SRUB_ID, 300L);
    }

    @Test
    @DisplayName("CONVERT idempotency returns existing op without re-burn/re-credit")
    void convertIdempotency() {
        SpasiboOperation existing = SpasiboOperation.builder()
                .id(UUID.randomUUID())
                .opType(SpasiboOperation.OpType.CONVERT)
                .userId(USER).points(100L).rubAmount(100L)
                .reference("convert-dup")
                .status(SpasiboOperation.Status.COMPLETED)
                .build();
        when(operationRepository.findByReference("convert-dup")).thenReturn(Optional.of(existing));

        SpasiboOperation result = spasiboService.convertToRub(USER, 100L, "convert-dup");

        assertSame(existing, result);
        verify(tokenService, never()).deductInternal(any(), any(), anyLong());
        verify(tokenService, never()).creditInternal(any(), any(), anyLong());
    }

    @Test
    @DisplayName("CONVERT order of operations: deduct first, then credit (prevents free SRUB)")
    void convertDeductBeforeCredit() {
        when(operationRepository.findByReference("convert-order")).thenReturn(Optional.empty());
        var inOrder = org.mockito.Mockito.inOrder(tokenService);

        spasiboService.convertToRub(USER, 100L, "convert-order");

        inOrder.verify(tokenService).deductInternal(USER, SSPAS_ID, 100L);
        inOrder.verify(tokenService).creditInternal(USER, SRUB_ID, 100L);
    }

    @Test
    @DisplayName("Persisted MINT row captures user + points + reference + opType")
    void mintPersistedRowShape() {
        when(operationRepository.findByReference("shape-test")).thenReturn(Optional.empty());
        ArgumentCaptor<SpasiboOperation> captor = ArgumentCaptor.forClass(SpasiboOperation.class);

        spasiboService.handleMintWebhook(USER, 777L, "shape-test");

        verify(operationRepository).save(captor.capture());
        SpasiboOperation saved = captor.getValue();
        assertEquals(SpasiboOperation.OpType.MINT, saved.getOpType());
        assertSame(USER, saved.getUserId());
        assertEquals(777L, saved.getPoints());
        assertNull(saved.getRubAmount());
        assertEquals("shape-test", saved.getReference());
        assertEquals(SpasiboOperation.Status.COMPLETED, saved.getStatus());
    }

    @Test
    @DisplayName("CONVERT rejects blank reference + non-positive points")
    void convertValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> spasiboService.convertToRub(USER, 0L, "ref"));
        assertThrows(IllegalArgumentException.class,
                () -> spasiboService.convertToRub(USER, 100L, ""));
    }
}
