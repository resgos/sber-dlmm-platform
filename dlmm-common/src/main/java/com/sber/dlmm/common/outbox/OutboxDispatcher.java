package com.sber.dlmm.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

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
}
