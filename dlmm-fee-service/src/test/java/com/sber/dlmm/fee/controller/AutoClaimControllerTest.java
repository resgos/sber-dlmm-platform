package com.sber.dlmm.fee.controller;

import com.sber.dlmm.fee.dto.AutoClaimPolicyDto;
import com.sber.dlmm.fee.entity.AutoClaimLog;
import com.sber.dlmm.fee.repository.AutoClaimLogRepository;
import com.sber.dlmm.fee.service.AutoClaimPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 12 G-16 — controller-level checks. We don't bring up a
 * MockMvc context (no Spring slice test) — Authentication is constructed
 * directly as the gateway filter would build it. Goal: ensure the
 * controller threads the principal UUID through to the service.
 */
class AutoClaimControllerTest {

    private AutoClaimPolicyService policyService;
    private AutoClaimLogRepository logRepository;
    private AutoClaimController controller;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        policyService = mock(AutoClaimPolicyService.class);
        logRepository = mock(AutoClaimLogRepository.class);
        controller = new AutoClaimController(policyService, logRepository);
    }

    @Test
    void get_returnsServiceResult_andThreadsUserId() {
        AutoClaimPolicyDto stored = new AutoClaimPolicyDto(true, BigDecimal.valueOf(500), 10, List.of("pool-z"));
        when(policyService.getOrDefault(userId)).thenReturn(stored);

        ResponseEntity<AutoClaimPolicyDto> response = controller.getPolicy(authFor(userId));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(stored);
        verify(policyService).getOrDefault(userId);
    }

    @Test
    void put_upsertsViaService() {
        AutoClaimPolicyDto incoming = new AutoClaimPolicyDto(true, BigDecimal.valueOf(2500), 5, List.of());
        when(policyService.upsert(eq(userId), any())).thenReturn(incoming);

        ResponseEntity<AutoClaimPolicyDto> response = controller.upsertPolicy(incoming, authFor(userId));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(incoming);
        verify(policyService).upsert(userId, incoming);
    }

    @Test
    void delete_resetsViaService() {
        AutoClaimPolicyDto def = new AutoClaimPolicyDto(false, BigDecimal.valueOf(1000), 20, List.of());
        when(policyService.reset(userId)).thenReturn(def);

        ResponseEntity<AutoClaimPolicyDto> response = controller.resetPolicy(authFor(userId));

        assertThat(response.getBody()).isEqualTo(def);
        verify(policyService).reset(userId);
    }

    @Test
    void history_returnsServiceResult_withClampedLimit() {
        AutoClaimLog log = AutoClaimLog.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .positionId(UUID.randomUUID())
                .poolId(UUID.randomUUID())
                .amountX(100)
                .amountY(200)
                .status(AutoClaimLog.Status.SUCCESS)
                .firedAt(LocalDateTime.now())
                .build();
        Page<AutoClaimLog> page = new PageImpl<>(List.of(log));
        when(logRepository.findByUserIdOrderByFiredAtDesc(eq(userId), any(PageRequest.class))).thenReturn(page);

        var response = controller.getHistory(50, authFor(userId));

        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).status()).isEqualTo(AutoClaimLog.Status.SUCCESS);
    }

    @Test
    void history_emptyResult_okay() {
        when(logRepository.findByUserIdOrderByFiredAtDesc(eq(userId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        var response = controller.getHistory(20, authFor(userId));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEmpty();
    }

    private static UsernamePasswordAuthenticationToken authFor(UUID userId) {
        return new UsernamePasswordAuthenticationToken(userId, "VERIFIED", List.of());
    }
}
