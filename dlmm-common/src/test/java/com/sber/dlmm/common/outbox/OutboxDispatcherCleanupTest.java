package com.sber.dlmm.common.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 9-DS-r4 (P0-5) — covers the cleanup branch of
 * {@link OutboxDispatcher}. We don't bring up Spring or a real DB:
 * the dispatcher is a thin orchestrator over the repository, so
 * verifying the call shape (or absence) is enough.
 *
 * <p>The dispatch tick itself is exercised end-to-end by the
 * service-level integration tests (FullSwapFlowIT).
 */
class OutboxDispatcherCleanupTest {

    private OutboxEventRepository repository;
    private KafkaTemplate<String, String> kafkaTemplate;
    private OutboxProperties properties;
    private OutboxDispatcher dispatcher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        properties = new OutboxProperties();
        properties.setServiceName("test-service");
        dispatcher = new OutboxDispatcher(repository, kafkaTemplate, properties);
    }

    @Test
    void cleanup_disabled_when_retentionDays_is_zero() {
        properties.setRetentionDays(0);

        dispatcher.cleanup();

        verify(repository, never()).deletePublishedBefore(any());
    }

    @Test
    void cleanup_disabled_when_retentionDays_is_negative() {
        // Negative is a misconfiguration; rather than treating it as
        // "delete everything in the future" we fail closed (no-op).
        properties.setRetentionDays(-3);

        dispatcher.cleanup();

        verify(repository, never()).deletePublishedBefore(any());
    }

    @Test
    void cleanup_uses_retentionDays_as_cutoff() {
        properties.setRetentionDays(7);
        when(repository.deletePublishedBefore(any())).thenReturn(42);

        LocalDateTime before = LocalDateTime.now();
        dispatcher.cleanup();
        LocalDateTime after = LocalDateTime.now();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).deletePublishedBefore(cutoff.capture());

        // Cutoff = now - 7d, computed inside cleanup(). Both bounds
        // were taken either side of the call so the captured value
        // must sit between them once shifted forward by 7d.
        LocalDateTime expectedLow = before.minusDays(7).minus(1, ChronoUnit.SECONDS);
        LocalDateTime expectedHigh = after.minusDays(7).plus(1, ChronoUnit.SECONDS);
        assertThat(cutoff.getValue()).isAfterOrEqualTo(expectedLow);
        assertThat(cutoff.getValue()).isBeforeOrEqualTo(expectedHigh);
    }
}
