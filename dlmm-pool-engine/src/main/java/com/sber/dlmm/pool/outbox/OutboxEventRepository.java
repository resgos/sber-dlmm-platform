package com.sber.dlmm.pool.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Service-scoped poll — pool-engine's dispatcher only sees its own rows.
     * Backed by partial index (service, created_at) WHERE published_at IS NULL.
     */
    @Query("SELECT e FROM OutboxEvent e WHERE e.service = :service AND e.publishedAt IS NULL ORDER BY e.createdAt ASC")
    List<OutboxEvent> findUnpublishedForService(String service, Pageable pageable);

    long countByServiceAndPublishedAtIsNull(String service);
}
