package com.sber.dlmm.common.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Shared outbox row. One per durable domain event. Written in the same
 * transaction as the domain mutation; shipped to Kafka asynchronously
 * by {@link OutboxDispatcher}.
 *
 * Sprint 3 #3.9 — extracted from per-service copies in token-service
 * and pool-engine. The {@code service} column tags the originator so
 * each service's dispatcher only sees rows it created (per-service
 * partial index in the shared table). The {@code published_at}
 * timestamp is the only mutable column once a row is created.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    /** Primary key; a client-generated {@link UUID} (assigned in {@link OutboxService#append}). */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Domain aggregate kind this event is about (e.g. "Pool", "Swap"). */
    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    /** Id of the specific aggregate instance; also used as the Kafka message key. */
    @Column(name = "aggregate_id", nullable = false, length = 128)
    private String aggregateId;

    /** Logical event name (e.g. "SwapExecuted"); carried for consumers/diagnostics. */
    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    /** Destination Kafka topic the dispatcher publishes this row to. */
    @Column(name = "topic", nullable = false, length = 128)
    private String topic;

    /** Serialised event body (JSON), sent verbatim as the Kafka message value. */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    /** When the row was appended (inside the business transaction); drives poll ordering. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** When the row was successfully shipped to Kafka; {@code null} ⇒ still pending. */
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    /** Number of failed publish attempts so far (bumped by {@link #recordFailure}). */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** Last publish error (truncated); for diagnostics on a stuck row. */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /** Originating service tag; each service's dispatcher drains only its own rows. */
    @Column(name = "service", nullable = false, length = 32)
    private String service;

    /** No-arg constructor required by JPA. */
    public OutboxEvent() { }

    /**
     * Creates a new, unpublished outbox row.
     *
     * @param id            primary key (a fresh {@link UUID})
     * @param aggregateType domain aggregate kind (e.g. "Pool")
     * @param aggregateId   aggregate instance id; also the Kafka message key
     * @param eventType     logical event name (e.g. "SwapExecuted")
     * @param topic         destination Kafka topic
     * @param payload       serialised event body (JSON)
     * @param createdAt     creation timestamp (within the business transaction)
     * @param service       originating service tag for dispatcher scoping
     */
    public OutboxEvent(UUID id, String aggregateType, String aggregateId,
                       String eventType, String topic, String payload,
                       LocalDateTime createdAt, String service) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
        this.createdAt = createdAt;
        this.service = service;
    }

    /** @return the primary key. */
    public UUID getId() { return id; }
    /** @return the domain aggregate kind (e.g. "Pool"). */
    public String getAggregateType() { return aggregateType; }
    /** @return the aggregate instance id (also the Kafka message key). */
    public String getAggregateId() { return aggregateId; }
    /** @return the logical event name (e.g. "SwapExecuted"). */
    public String getEventType() { return eventType; }
    /** @return the destination Kafka topic. */
    public String getTopic() { return topic; }
    /** @return the serialised event body (JSON Kafka value). */
    public String getPayload() { return payload; }
    /** @return when the row was appended. */
    public LocalDateTime getCreatedAt() { return createdAt; }
    /** @return when the row was published to Kafka, or {@code null} if still pending. */
    public LocalDateTime getPublishedAt() { return publishedAt; }
    /** @return the number of failed publish attempts so far. */
    public int getAttempts() { return attempts; }
    /** @return the last publish error (truncated), or {@code null} if none. */
    public String getLastError() { return lastError; }
    /** @return the originating service tag. */
    public String getService() { return service; }

    /** Marks this row as published by stamping {@link #publishedAt} with the current time. */
    public void markPublished() {
        this.publishedAt = LocalDateTime.now();
    }

    /**
     * Records a failed publish attempt: increments {@link #attempts} and stores
     * the (≤ 1000-char truncated) error message in {@link #lastError}. The row
     * stays unpublished so the next dispatcher tick retries it.
     *
     * @param message the failure description; {@code null} clears {@link #lastError}
     */
    public void recordFailure(String message) {
        this.attempts++;
        this.lastError = message == null ? null : message.substring(0, Math.min(message.length(), 1000));
    }
}
