package com.sber.dlmm.transaction.repository;

import com.sber.dlmm.common.enums.B2BSettlementStatus;
import com.sber.dlmm.transaction.entity.B2BSettlement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link B2BSettlement} records.
 *
 * <p>Exposes the {@code reference}-based idempotency lookup (a duplicate
 * POST resolves to the existing settlement instead of re-executing) and
 * the paged list query that lets a corp account see settlements on either
 * side of the transfer, with optional status and date filters.
 */
public interface B2BSettlementRepository extends JpaRepository<B2BSettlement, UUID> {

    /**
     * Idempotency lookup by the caller-supplied unique business reference.
     *
     * @param reference the unique reference the corp ERP stamped on the request
     * @return the existing settlement under that reference, or empty if it is new
     */
    Optional<B2BSettlement> findByReference(String reference);

    /**
     * Sprint 4 #4.6 — list endpoint: a corp account can see settlements
     * where it's either side of the transfer. Date filters optional
     * (a null {@code status}, {@code fromDate} or {@code toDate} disables
     * that predicate).
     *
     * @param userId   account that must appear as either the from- or to-side
     * @param status   status filter, or null for any status
     * @param fromDate inclusive lower bound on {@code createdAt}, or null for unbounded
     * @param toDate   inclusive upper bound on {@code createdAt}, or null for unbounded
     * @param pageable page number, size and sort
     * @return a page of settlements involving the account and matching the filters
     */
    @Query("SELECT s FROM B2BSettlement s " +
           "WHERE (s.fromUserId = :userId OR s.toUserId = :userId) " +
           "AND (:status IS NULL OR s.status = :status) " +
           "AND (:fromDate IS NULL OR s.createdAt >= :fromDate) " +
           "AND (:toDate IS NULL OR s.createdAt <= :toDate)")
    Page<B2BSettlement> findForUser(@Param("userId") UUID userId,
                                     @Param("status") B2BSettlementStatus status,
                                     @Param("fromDate") LocalDateTime fromDate,
                                     @Param("toDate") LocalDateTime toDate,
                                     Pageable pageable);
}
