package com.sber.dlmm.token.service;

import com.sber.dlmm.token.entity.SpasiboWritebackEntry;
import com.sber.dlmm.token.entity.SpasiboWritebackEntry.ReasonCodes;
import com.sber.dlmm.token.entity.SpasiboWritebackEntry.Status;
import com.sber.dlmm.token.repository.SpasiboWritebackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 9-DS-r4 (P1-17) — pins {@link SpasiboWritebackService}
 * accrual contract: rate schedule, anti-gaming threshold, daily +
 * monthly caps, idempotency, and the disabled-feature short-circuit.
 *
 * <p>Doesn't test the {@code flush} branch end-to-end — that
 * requires Spring scheduling. The MVP flush logic is a single-line
 * stub anyway (logs + marks ACCEPTED); the actual HTTP path lands
 * with the Spasibo BU contract.
 */
class SpasiboWritebackServiceTest {

    private SpasiboWritebackRepository repo;
    private SpasiboWritebackService service;

    private static final long CAP_DAY = 10_000L;
    private static final long CAP_MONTH = 200_000L;
    private static final long MIN_SWAP = 10_000L;
    private static final long MIN_HEDGE = 100_000L;

    @BeforeEach
    void setUp() {
        repo = mock(SpasiboWritebackRepository.class);
        // Default: no prior accruals, no idempotency hit.
        when(repo.sumPointsByUserSince(any(), any())).thenReturn(0L);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new SpasiboWritebackService(
                repo,
                /*enabled*/ true,
                /*endpoint*/ "",
                CAP_DAY,
                CAP_MONTH,
                MIN_SWAP,
                MIN_HEDGE,
                /*maxAttempts*/ 3,
                /*batchSize*/ 100);
    }

    @Test
    void accrues_at_swap_rate() {
        // 1_000_000 base units × 10 bps = 1000 points.
        Optional<SpasiboWritebackEntry> result = service.accrue(
                UUID.randomUUID(), UUID.randomUUID(), ReasonCodes.SWAP, 1_000_000L);
        assertThat(result).isPresent();
        assertThat(result.get().getAmountPoints()).isEqualTo(1000);
        assertThat(result.get().getStatus()).isEqualTo(Status.PENDING);
    }

    @Test
    void accrues_at_hedge_rate() {
        // Hedge needs to be above the higher hedge threshold (100k base).
        // 1_000_000 base × 50 bps = 5000 points.
        Optional<SpasiboWritebackEntry> result = service.accrue(
                UUID.randomUUID(), UUID.randomUUID(), ReasonCodes.HEDGE, 1_000_000L);
        assertThat(result).isPresent();
        assertThat(result.get().getAmountPoints()).isEqualTo(5000);
    }

    @Test
    void below_swap_threshold_returns_empty() {
        // 5_000 base < 10_000 threshold ⇒ skipped (anti-gaming).
        Optional<SpasiboWritebackEntry> result = service.accrue(
                UUID.randomUUID(), UUID.randomUUID(), ReasonCodes.SWAP, 5_000L);
        assertThat(result).isEmpty();
        verify(repo, never()).save(any());
    }

    @Test
    void below_hedge_threshold_returns_empty() {
        // 50_000 base < 100_000 hedge threshold ⇒ skipped.
        Optional<SpasiboWritebackEntry> result = service.accrue(
                UUID.randomUUID(), UUID.randomUUID(), ReasonCodes.HEDGE, 50_000L);
        assertThat(result).isEmpty();
    }

    @Test
    void daily_cap_refuses_overflow() {
        // User already at 9_500 points today; a 1_000-point accrual
        // (10_000_000 base × 10 bps) would push total to 10_500 > 10_000 cap.
        UUID user = UUID.randomUUID();
        when(repo.sumPointsByUserSince(any(), any())).thenReturn(9_500L);
        Optional<SpasiboWritebackEntry> result = service.accrue(
                UUID.randomUUID(), user, ReasonCodes.SWAP, 10_000_000L);
        assertThat(result).isEmpty();
        verify(repo, never()).save(any());
    }

    @Test
    void monthly_cap_refuses_overflow() {
        // First call: daily lookup (0); second call: monthly lookup (199_500).
        // A 1_000-point accrual would push monthly to 200_500 > 200_000 cap.
        when(repo.sumPointsByUserSince(any(), any()))
                .thenReturn(0L)        // daily window
                .thenReturn(199_500L); // monthly window
        Optional<SpasiboWritebackEntry> result = service.accrue(
                UUID.randomUUID(), UUID.randomUUID(), ReasonCodes.SWAP, 10_000_000L);
        assertThat(result).isEmpty();
    }

    @Test
    void idempotent_on_duplicate_dlmm_tx_id() {
        UUID dup = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        SpasiboWritebackEntry existing = SpasiboWritebackEntry.builder()
                .id(UUID.randomUUID())
                .dlmmTxId(dup)
                .userId(user)
                .amountPoints(100)
                .reasonCode(ReasonCodes.SWAP)
                .status(Status.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        // Simulate the UNIQUE constraint trip + existing row lookup.
        when(repo.save(any())).thenThrow(
                new org.springframework.dao.DataIntegrityViolationException("dup"));
        when(repo.findByDlmmTxId(dup)).thenReturn(Optional.of(existing));

        Optional<SpasiboWritebackEntry> result = service.accrue(
                dup, user, ReasonCodes.SWAP, 1_000_000L);

        assertThat(result).contains(existing);
    }

    @Test
    void disabled_service_returns_empty_without_db_call() {
        SpasiboWritebackService disabled = new SpasiboWritebackService(
                repo, /*enabled*/ false, "", CAP_DAY, CAP_MONTH,
                MIN_SWAP, MIN_HEDGE, 3, 100);
        Optional<SpasiboWritebackEntry> result = disabled.accrue(
                UUID.randomUUID(), UUID.randomUUID(), ReasonCodes.SWAP, 1_000_000L);
        assertThat(result).isEmpty();
        verify(repo, never()).sumPointsByUserSince(any(), any());
        verify(repo, never()).save(any());
    }

    @Test
    void unknown_reason_code_returns_empty() {
        // Unknown reasons get 0bps rate and skip; protects against
        // future event types accidentally accruing.
        Optional<SpasiboWritebackEntry> result = service.accrue(
                UUID.randomUUID(), UUID.randomUUID(), "UNKNOWN_REASON", 1_000_000L);
        assertThat(result).isEmpty();
    }

    @Test
    void flush_stub_marks_pending_entries_accepted() {
        SpasiboWritebackEntry e = SpasiboWritebackEntry.builder()
                .id(UUID.randomUUID())
                .dlmmTxId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .amountPoints(100)
                .reasonCode(ReasonCodes.SWAP)
                .status(Status.PENDING)
                .attemptCount(0)
                .createdAt(LocalDateTime.now())
                .build();
        when(repo.findShippable(any(), any())).thenReturn(List.of(e));

        service.flush();

        // Stub branch (no endpoint configured) always succeeds.
        ArgumentCaptor<List<SpasiboWritebackEntry>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(repo, atLeastOnce()).saveAll(captor.capture());
        SpasiboWritebackEntry shipped = captor.getValue().get(0);
        assertThat(shipped.getStatus()).isEqualTo(Status.ACCEPTED);
        assertThat(shipped.getSettledAt()).isNotNull();
        assertThat(shipped.getSpasiboTxId()).startsWith("stub-");
    }

    @Test
    void flush_skips_when_disabled() {
        SpasiboWritebackService disabled = new SpasiboWritebackService(
                repo, /*enabled*/ false, "", CAP_DAY, CAP_MONTH,
                MIN_SWAP, MIN_HEDGE, 3, 100);
        disabled.flush();
        verify(repo, never()).findShippable(any(), any());
        verify(repo, never()).saveAll(any());
    }

    @Test
    void flush_with_empty_queue_is_a_noop() {
        when(repo.findShippable(any(), any())).thenReturn(List.of());
        service.flush();
        verify(repo, never()).saveAll(any());
    }

    @Test
    void zero_or_negative_amount_returns_empty() {
        assertThat(service.accrue(UUID.randomUUID(), UUID.randomUUID(), ReasonCodes.SWAP, 0L))
                .isEmpty();
        assertThat(service.accrue(UUID.randomUUID(), UUID.randomUUID(), ReasonCodes.SWAP, -1L))
                .isEmpty();
        verify(repo, never()).save(any());
    }

    @Test
    void null_inputs_short_circuit_safely() {
        assertThat(service.accrue(null, UUID.randomUUID(), ReasonCodes.SWAP, 1_000_000L))
                .isEmpty();
        assertThat(service.accrue(UUID.randomUUID(), null, ReasonCodes.SWAP, 1_000_000L))
                .isEmpty();
        assertThat(service.accrue(UUID.randomUUID(), UUID.randomUUID(), null, 1_000_000L))
                .isEmpty();
        verify(repo, never()).save(any());
        // sumPointsByUserSince might be called for the success path
        // but should never be called when inputs are null.
        verify(repo, times(0)).sumPointsByUserSince(any(), any());
    }
}
