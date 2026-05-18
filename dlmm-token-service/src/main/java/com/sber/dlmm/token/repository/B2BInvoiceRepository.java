package com.sber.dlmm.token.repository;

import com.sber.dlmm.token.entity.B2BInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface B2BInvoiceRepository extends JpaRepository<B2BInvoice, UUID> {

    Optional<B2BInvoice> findByIssuerIdAndPeriodStart(UUID issuerId, LocalDate periodStart);

    List<B2BInvoice> findByIssuerIdOrderByPeriodStartDesc(UUID issuerId);

    /** Has any invoice ever been issued for this issuer? Used to decide
     *  whether the listing fee is owed in this period. */
    boolean existsByIssuerId(UUID issuerId);
}
