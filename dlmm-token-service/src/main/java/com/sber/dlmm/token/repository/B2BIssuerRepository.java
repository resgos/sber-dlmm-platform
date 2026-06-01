package com.sber.dlmm.token.repository;

import com.sber.dlmm.token.entity.B2BIssuer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for the {@link B2BIssuer} aggregate (corporate
 * token issuers and their KYB state).
 *
 * <p>Adds lookup by the issuer's ИНН business key and a KYB-status filter
 * (used by the admin review queue) on top of the inherited CRUD.
 */
@Repository
public interface B2BIssuerRepository extends JpaRepository<B2BIssuer, UUID> {

    /**
     * Looks up an issuer by Russian taxpayer number (ИНН).
     *
     * @param inn the issuer's ИНН (matches the DB-unique {@code inn} column)
     * @return the matching issuer, or empty if none is registered under that ИНН
     */
    Optional<B2BIssuer> findByInn(String inn);

    /**
     * Lists every issuer in a given KYB review state — e.g. all
     * {@link B2BIssuer.KybStatus#PENDING} issuers for the admin approval
     * queue. No guaranteed ordering.
     *
     * @param status the KYB status to filter by
     * @return issuers in that status (possibly empty)
     */
    List<B2BIssuer> findByKybStatus(B2BIssuer.KybStatus status);
}
