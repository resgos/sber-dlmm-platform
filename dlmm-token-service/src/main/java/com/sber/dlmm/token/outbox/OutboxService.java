package com.sber.dlmm.token.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Single entrypoint for appending to the transactional outbox. Callers
 * use this instead of {@code kafkaTemplate.send(...)} directly so the
 * event lands in the same DB transaction as the domain mutation.
 *
 * If the surrounding @Transactional rolls back, the outbox row rolls
 * back with it — no ghost events. If the dispatcher hasn't sent yet
 * when the service crashes, the next poll picks it up — no lost events.
 */
@Service
public class OutboxService {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Append must run inside the caller's transaction — that's the whole
     * point of the pattern. MANDATORY propagation makes that explicit:
     * calling append() without an active @Transactional throws, which
     * surfaces the bug at the call site instead of silently allowing
     * a dual-write.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String aggregateType, String aggregateId, String eventType,
                       String topic, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Fail loud — a malformed event payload should not silently
            // bypass the outbox. The whole transaction (including the
            // balance mutation) rolls back.
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
                LocalDateTime.now()
        );
        repository.save(event);
    }
}
