package com.sber.dlmm.token.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Dispatcher poll query. Partial index on (created_at) WHERE published_at IS NULL
     * means this is O(unpublished) regardless of how much history we've published.
     * Limit comes from the Pageable — dispatcher should pass a small batch
     * (50–200) to keep one tick responsive.
     */
    @Query("SELECT e FROM OutboxEvent e WHERE e.publishedAt IS NULL ORDER BY e.createdAt ASC")
    List<OutboxEvent> findUnpublished(Pageable pageable);

    long countByPublishedAtIsNull();
}
