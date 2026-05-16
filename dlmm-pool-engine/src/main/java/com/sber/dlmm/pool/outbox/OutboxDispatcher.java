package com.sber.dlmm.pool.outbox;

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
 * Pool-engine outbox dispatcher. Scans {@code outbox_events}
 * filtered to {@code service = 'pool-engine'} and ships unpublished rows
 * to Kafka. See {@code OutboxService.SERVICE_NAME} for the tag string.
 *
 * Identical mechanics to token-service's dispatcher: 500ms tick,
 * 100-row batches, 3s per-record send timeout, retries on failure
 * via {@code attempts}/{@code last_error}.
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int BATCH_SIZE = 100;
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
        List<OutboxEvent> batch = repository.findUnpublishedForService(
                OutboxService.SERVICE_NAME, PageRequest.of(0, BATCH_SIZE));
        if (batch.isEmpty()) return;

        int sent = 0;
        int failed = 0;
        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate
                        .send(event.getTopic(), event.getAggregateId(), event.getPayload())
                        .get(SEND_TIMEOUT_SEC, TimeUnit.SECONDS);
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
                    repository.countByServiceAndPublishedAtIsNull(OutboxService.SERVICE_NAME));
        } else if (failed > 0) {
            log.info("Outbox dispatch tick had {} failure(s); will retry next tick", failed);
        }
    }
}
