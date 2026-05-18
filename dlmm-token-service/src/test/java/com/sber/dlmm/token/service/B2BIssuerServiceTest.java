package com.sber.dlmm.token.service;

import com.sber.dlmm.token.entity.B2BIssuer;
import com.sber.dlmm.token.repository.B2BIssuerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class B2BIssuerServiceTest {

    @Mock
    private B2BIssuerRepository issuerRepository;

    @InjectMocks
    private B2BIssuerService issuerService;

    @BeforeEach
    void setUp() {
        lenient().when(issuerRepository.save(any(B2BIssuer.class)))
                .thenAnswer(inv -> {
                    B2BIssuer i = inv.getArgument(0);
                    if (i.getId() == null) i.setId(UUID.randomUUID());
                    return i;
                });
    }

    @Test
    @DisplayName("register: new INN inserts row with PENDING status + BASIC tier default")
    void registerNew() {
        when(issuerRepository.findByInn("7700000001")).thenReturn(Optional.empty());

        B2BIssuer iss = issuerService.register("7700000001", "ООО Тест",
                "Test Corp", "ceo@test.ru", "+7-495-000-0000", null);

        assertNotNull(iss.getId());
        assertEquals("7700000001", iss.getInn());
        assertEquals(B2BIssuer.KybStatus.PENDING, iss.getKybStatus());
        assertEquals(B2BIssuer.Tier.BASIC, iss.getTier(), "null tier defaults to BASIC");
    }

    @Test
    @DisplayName("register: duplicate INN returns existing row (idempotency)")
    void registerDuplicateReturnsExisting() {
        B2BIssuer existing = B2BIssuer.builder().id(UUID.randomUUID())
                .inn("7700000002").legalName("ООО Существ").displayName("Existing")
                .contactEmail("e@e.ru").tier(B2BIssuer.Tier.PRO)
                .kybStatus(B2BIssuer.KybStatus.APPROVED).build();
        when(issuerRepository.findByInn("7700000002")).thenReturn(Optional.of(existing));

        B2BIssuer result = issuerService.register("7700000002", "Different Name",
                "Different Display", "x@x.ru", null, B2BIssuer.Tier.ENTERPRISE);

        assertSame(existing, result, "should return existing row regardless of input");
        verify(issuerRepository, never()).save(any());
    }

    @Test
    @DisplayName("approve: flips PENDING → APPROVED, sets reviewer + timestamp")
    void approveFlipsStatus() {
        UUID issuerId = UUID.randomUUID();
        UUID reviewer = UUID.randomUUID();
        B2BIssuer iss = B2BIssuer.builder().id(issuerId).inn("1").legalName("X")
                .displayName("X").contactEmail("x@x.ru")
                .tier(B2BIssuer.Tier.BASIC)
                .kybStatus(B2BIssuer.KybStatus.PENDING).build();
        when(issuerRepository.findById(issuerId)).thenReturn(Optional.of(iss));

        B2BIssuer result = issuerService.approve(issuerId, reviewer);

        assertEquals(B2BIssuer.KybStatus.APPROVED, result.getKybStatus());
        assertEquals(reviewer, result.getReviewedBy());
        assertNotNull(result.getReviewedAt());
    }

    @Test
    @DisplayName("approve: already-APPROVED is a no-op (returns existing, no save)")
    void approveAlreadyApprovedIsNoop() {
        UUID issuerId = UUID.randomUUID();
        B2BIssuer iss = B2BIssuer.builder().id(issuerId).inn("1").legalName("X")
                .displayName("X").contactEmail("x@x.ru")
                .tier(B2BIssuer.Tier.BASIC)
                .kybStatus(B2BIssuer.KybStatus.APPROVED).build();
        when(issuerRepository.findById(issuerId)).thenReturn(Optional.of(iss));

        issuerService.approve(issuerId, UUID.randomUUID());

        verify(issuerRepository, never()).save(any());
    }

    @Test
    @DisplayName("reject: sets REJECTED + reviewer + reason")
    void rejectStoresReason() {
        UUID issuerId = UUID.randomUUID();
        UUID reviewer = UUID.randomUUID();
        B2BIssuer iss = B2BIssuer.builder().id(issuerId).inn("1").legalName("X")
                .displayName("X").contactEmail("x@x.ru")
                .tier(B2BIssuer.Tier.BASIC)
                .kybStatus(B2BIssuer.KybStatus.PENDING).build();
        when(issuerRepository.findById(issuerId)).thenReturn(Optional.of(iss));

        B2BIssuer result = issuerService.reject(issuerId, reviewer,
                "ЕГРЮЛ: запись о юр. лице отсутствует");

        assertEquals(B2BIssuer.KybStatus.REJECTED, result.getKybStatus());
        assertEquals(reviewer, result.getReviewedBy());
        assertEquals("ЕГРЮЛ: запись о юр. лице отсутствует", result.getRejectionReason());
    }

    @Test
    @DisplayName("approve missing issuer throws IllegalArgumentException")
    void approveMissingThrows() {
        UUID missing = UUID.randomUUID();
        when(issuerRepository.findById(missing)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> issuerService.approve(missing, UUID.randomUUID()));
    }
}
