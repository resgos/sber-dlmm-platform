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

public interface B2BSettlementRepository extends JpaRepository<B2BSettlement, UUID> {

    Optional<B2BSettlement> findByReference(String reference);

    /**
     * Sprint 4 #4.6 — list endpoint: a corp account can see settlements
     * where it's either side of the transfer. Date filters optional.
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
