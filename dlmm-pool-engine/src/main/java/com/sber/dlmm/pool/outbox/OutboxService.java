package com.sber.dlmm.pool.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Pool-engine's outbox append entry-point. Used in place of
 * {@code kafkaTemplate.send(...)} so events land in the same DB transaction
 * as the pool/position mutation. See companion class in dlmm-token-service
 * for the design rationale.
 *
 * Propagation.MANDATORY: calling without an active @Transactional throws,
 * which surfaces the dual-write bug at the call site instead of silently
 * losing the atomicity guarantee.
 */
@Service
public class OutboxService {

    public static final String SERVICE_NAME = "pool-engine";

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

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
                SERVICE_NAME
        );
        repository.save(event);
    }
}
