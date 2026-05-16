package com.sber.dlmm.token.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Polls the {@code outbox_events} table and ships any unpublished rows
 * to Kafka. Runs every 500ms by default — tight enough that downstream
 * latency stays sub-second under normal load, loose enough that a
 * dispatcher tick can't dominate DB time.
 *
 * On Kafka failure, the row stays {@code published_at IS NULL} and
 * {@code attempts} is incremented — the next tick retries automatically.
 * No external retry scheduler, no dead-letter table needed for MVP;
 * those are Sprint 2 work once we have load-test data on the failure
 * mode mix.
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int BATCH_SIZE = 100;
    /** Per-record Kafka send timeout — keeps a wedged broker from stalling the tick. */
    private static final long SEND_TIMEOUT_SEC = 3;

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxDispatcher(OutboxEventRepository repository,
                            KafkaTemplate<String, String> kafkaTemplate) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelayString = "${dlmm.outbox.dispatch-interval-ms:500}")
    @Transactional
    public void dispatch() {
        List<OutboxEvent> batch = repository.findUnpublished(PageRequest.of(0, BATCH_SIZE));
        if (batch.isEmpty()) return;

        int sent = 0;
        int failed = 0;
        for (OutboxEvent event : batch) {
            try {
                // .get() blocks; the per-record timeout is the safety net.
                // We do this serially on purpose — strict per-key ordering
                // by created_at matters for consumers that build state
                // (e.g. user balance projection).
                kafkaTemplate
                        .send(event.getTopic(), event.getAggregateId(), event.getPayload())
                        .get(SEND_TIMEOUT_SEC, TimeUnit.SECONDS);
                event.markPublished();
                sent++;
            } catch (Exception ex) {
                event.recordFailure(ex.toString());
                failed++;
                // Don't bail out of the loop — failures are typically per-broker-partition
                // transient. We want the rest of the batch to make progress.
                log.warn("Outbox publish failed for event {} (attempt {}): {}",
                        event.getId(), event.getAttempts(), ex.toString());
            }
        }
        // repository.save not needed — JPA dirty checking flushes on commit.
        if (log.isDebugEnabled()) {
            log.debug("Outbox dispatch tick: sent={}, failed={}, unpublished_remaining={}",
                    sent, failed, repository.countByPublishedAtIsNull());
        } else if (failed > 0) {
            log.info("Outbox dispatch tick had {} failure(s); will retry next tick", failed);
        }
    }
}
