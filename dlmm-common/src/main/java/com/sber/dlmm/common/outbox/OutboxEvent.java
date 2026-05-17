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

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 128)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 128)
    private String topic;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "service", nullable = false, length = 32)
    private String service;

    public OutboxEvent() { }

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

    public UUID getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getTopic() { return topic; }
    public String getPayload() { return payload; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public String getService() { return service; }

    public void markPublished() {
        this.publishedAt = LocalDateTime.now();
    }

    public void recordFailure(String message) {
        this.attempts++;
        this.lastError = message == null ? null : message.substring(0, Math.min(message.length(), 1000));
    }
}
