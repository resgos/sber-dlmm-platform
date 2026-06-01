package com.sber.dlmm.token.repository;

import com.sber.dlmm.token.entity.B2BInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for the {@link B2BInvoice} aggregate (monthly B2B
 * billing).
 *
 * <p>Supports the billing engine's needs: fetch a specific issuer/period
 * invoice (the unique billing key), list an issuer's invoice history, and
 * test whether an issuer has ever been invoiced (to decide first-period
 * listing-fee charging).
 */
@Repository
public interface B2BInvoiceRepository extends JpaRepository<B2BInvoice, UUID> {

    /**
     * Fetches the invoice for a specific issuer and billing period start.
     * Backs the {@code (issuer_id, period_start)} unique-key lookup that
     * prevents double-billing the same period.
     *
     * @param issuerId    the issuer's id
     * @param periodStart the first day of the billing period
     * @return the matching invoice, or empty if that period has not been billed
     */
    Optional<B2BInvoice> findByIssuerIdAndPeriodStart(UUID issuerId, LocalDate periodStart);

    /**
     * Lists all invoices for an issuer, newest billing period first
     * (ordered by {@code periodStart} descending) — the issuer billing
     * history view.
     *
     * @param issuerId the issuer's id
     * @return the issuer's invoices, most-recent period first (possibly empty)
     */
    List<B2BInvoice> findByIssuerIdOrderByPeriodStartDesc(UUID issuerId);

    /** Has any invoice ever been issued for this issuer? Used to decide
     *  whether the listing fee is owed in this period.
     *
     * @param issuerId the issuer's id
     * @return true iff at least one invoice already exists for the issuer */
    boolean existsByIssuerId(UUID issuerId);
}
