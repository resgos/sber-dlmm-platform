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

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final String serviceName;

    public OutboxService(OutboxEventRepository repository,
                         ObjectMapper objectMapper,
                         OutboxProperties properties) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.serviceName = properties.getServiceName();
    }

    /**
     * MANDATORY propagation: calling without an active @Transactional
     * throws. Surfaces the dual-write bug at the call site instead of
     * silently letting the event publish even though the domain
     * mutation rolled back.
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
