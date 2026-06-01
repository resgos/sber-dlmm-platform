package com.sber.dlmm.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Single entrypoint for appending to the transactional outbox.
 *
 * Callers use this instead of {@code kafkaTemplate.send(...)} so the
 * event lands in the same DB transaction as the domain mutation. If
 * the surrounding @Transactional rolls back, the outbox row rolls
 * back with it — no ghost events. If the dispatcher hasn't sent yet
 * when the service crashes, the next poll picks it up — no lost events.
 *
 * Sprint 3 #3.9: extracted from per-service copies into dlmm-common.
 * Service identity comes from {@link OutboxProperties#getServiceName()}
 * rather than a hardcoded constant — same class works for every service
 * once they set {@code dlmm.outbox.service-name} in their YAML.
 *
 * Bean is wired via {@link DlmmOutboxAutoConfiguration}, NOT @Service,
 * so it auto-activates only when JPA + Kafka + the service-name
 * property are all present.
 */
public class OutboxService {

    /** JPA repository the appended rows are persisted through. */
    private final OutboxEventRepository repository;
    /** Jackson mapper used to serialise the event payload to JSON. */
    private final ObjectMapper objectMapper;
    /** This service's tag, written on every row so its own dispatcher drains it. */
    private final String serviceName;

    /**
     * @param repository   repository used to persist outbox rows
     * @param objectMapper Jackson mapper for serialising payloads
     * @param properties   outbox config; supplies the {@code service-name} tag
     */
    public OutboxService(OutboxEventRepository repository,
                         ObjectMapper objectMapper,
                         OutboxProperties properties) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.serviceName = properties.getServiceName();
    }

    /**
     * Appends one event to the outbox, enlisted in the caller's transaction.
     *
     * <p>MANDATORY propagation: calling without an active @Transactional
     * throws. Surfaces the dual-write bug at the call site instead of
     * silently letting the event publish even though the domain
     * mutation rolled back. The row is shipped to Kafka later by
     * {@link OutboxDispatcher}.
     *
     * @param aggregateType domain aggregate kind (e.g. "Pool")
     * @param aggregateId   aggregate instance id; also used as the Kafka key
     * @param eventType     logical event name (e.g. "SwapExecuted")
     * @param topic         destination Kafka topic
     * @param payload       event body; serialised to JSON via the {@link ObjectMapper}
     * @throws IllegalStateException if {@code payload} cannot be serialised to JSON
     * @throws org.springframework.transaction.IllegalTransactionStateException
     *         if invoked without an active transaction (MANDATORY propagation)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String aggregateType, String aggregateId, String eventType,
                       String topic, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise outbox payload for "
                    + eventType + ": " + e.getMessage(), e);
        }
        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID(),
                aggregateType,
                aggregateId,
                eventType,
                topic,
                json,
                LocalDateTime.now(),
                serviceName
        );
        repository.save(event);
    }
}
