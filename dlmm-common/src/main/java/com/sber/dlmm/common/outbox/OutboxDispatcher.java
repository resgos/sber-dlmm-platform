package com.sber.dlmm.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Polls the shared {@code outbox_events} table for rows tagged with
 * <em>this service's</em> name and ships them to Kafka.
 *
 * Service identity comes from {@link OutboxProperties#getServiceName()}
 * — the same dispatcher class runs in every service; each one only
 * sees its own rows thanks to the {@code service} column filter.
 *
 * Sprint 3 #3.9: extracted from per-service copies. Failure handling
 * is unchanged: on Kafka send failure, the row stays unpublished and
 * {@code attempts} bumps. Next tick retries.
 */
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;

    public OutboxDispatcher(OutboxEventRepository repository,
                            KafkaTemplate<String, String> kafkaTemplate,
                            OutboxProperties properties) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${dlmm.outbox.dispatch-interval-ms:500}")
    @Transactional
    public void dispatch() {
        List<OutboxEvent> batch = repository.findUnpublishedForService(
                properties.getServiceName(),
                PageRequest.of(0, properties.getBatchSize()));
        if (batch.isEmpty()) return;

        int sent = 0;
        int failed = 0;
        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate
                        .send(event.getTopic(), event.getAggregateId(), event.getPayload())
                        .get(properties.getSendTimeoutSec(), TimeUnit.SECONDS);
                event.markPublished();
                sent++;
            } catch (Exception ex) {
                event.recordFailure(ex.toString());
                failed++;
                log.warn("Outbox publish failed for event {} (attempt {}): {}",
                        event.getId(), event.getAttempts(), ex.toString());
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Outbox dispatch tick: sent={}, failed={}, unpublished_remaining={}",
                    sent, failed,
                    repository.countByServiceAndPublishedAtIsNull(properties.getServiceName()));
        } else if (failed > 0) {
            log.info("Outbox dispatch tick had {} failure(s); will retry next tick", failed);
        }
    }

    /**
     * Sprint 9-DS-r4 (P0-5) — daily cleanup of long-published rows.
     *
     * <p>The outbox is a publish guarantee, not a system of record:
     * once a row is on Kafka, retaining it indefinitely only inflates
     * the table (~1.8k rows / day at idle in dev). Replays go through
     * Kafka topic offsets, never through the outbox, so deleting old
     * published rows is safe.
     *
     * <p>Set {@code dlmm.outbox.retention-days=0} to disable
     * (dev / tests). The cron in {@code dlmm.outbox.cleanup-cron}
     * controls when this fires; default is 03:17 daily — off-peak,
     * doesn't collide with hourly batch jobs at minute 0.
     *
     * <p>This runs in every service that uses the outbox; each one
     * deletes its own service's rows by virtue of the shared cutoff
     * timestamp (the DELETE is service-agnostic — we don't need
     * per-service scoping because every row has the same retention
     * policy and the same cutoff trims them all in one statement).
     */
    @Scheduled(cron = "${dlmm.outbox.cleanup-cron:0 17 3 * * *}")
    @Transactional
    public void cleanup() {
        int retentionDays = properties.getRetentionDays();
        if (retentionDays <= 0) {
            log.debug("Outbox cleanup disabled (retention-days={})", retentionDays);
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int deleted = repository.deletePublishedBefore(cutoff);
        if (deleted > 0) {
            log.info("Outbox cleanup: deleted {} published row(s) older than {} ({}d retention)",
                    deleted, cutoff, retentionDays);
        } else {
            log.debug("Outbox cleanup: no rows older than {} ({}d retention)", cutoff, retentionDays);
        }
    }
}
