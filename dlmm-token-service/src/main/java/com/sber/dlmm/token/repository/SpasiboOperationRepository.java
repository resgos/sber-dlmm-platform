package com.sber.dlmm.token.repository;

import com.sber.dlmm.token.entity.SpasiboOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for the {@link SpasiboOperation} aggregate (the
 * SberSpasibo mint/convert audit + idempotency log).
 *
 * <p>Beyond the inherited CRUD, exposes the reference-based lookup that
 * backs idempotent replay of Spasibo operations.
 */
@Repository
public interface SpasiboOperationRepository extends JpaRepository<SpasiboOperation, UUID> {

    /**
     * Fetches the operation recorded under a given external reference. Used
     * as the idempotency check: a replayed request with an already-seen
     * reference returns the existing row instead of creating a duplicate.
     *
     * @param reference the platform-wide unique external reference
     * @return the existing operation for that reference, or empty if none
     */
    Optional<SpasiboOperation> findByReference(String reference);
}
