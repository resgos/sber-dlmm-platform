package com.sber.dlmm.common.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

/**
 * Shared outbox repository. Not annotated @Repository so that
 * {@link DlmmOutboxAutoConfiguration} can register it explicitly with
 * @EnableJpaRepositories — keeps the consuming service's JPA
 * configuration from accidentally picking it up via package scan.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Service-scoped poll — each service's dispatcher only sees its
     * own rows. Backed by the partial index
     * `(service, created_at) WHERE published_at IS NULL`.
     */
    @Query("SELECT e FROM OutboxEvent e WHERE e.service = :service AND e.publishedAt IS NULL ORDER BY e.createdAt ASC")
    List<OutboxEvent> findUnpublishedForService(String service, Pageable pageable);

    long countByServiceAndPublishedAtIsNull(String service);
}
