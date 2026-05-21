package com.sber.dlmm.transaction.repository;

import com.sber.dlmm.common.enums.TransactionStatus;
import com.sber.dlmm.common.enums.TransactionType;
import com.sber.dlmm.transaction.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    /**
     * Sprint 9-DS-r4 (P0-4) — second dedup key for the swap event
     * consumer. The {@code SwapExecuted} envelope always carries the
     * pool-engine row UUID; this lookup lets us recognise a
     * re-delivered event even when the originating swap had no
     * client-supplied idempotencyKey.
     */
    Optional<Transaction> findByPoolEngineTxId(UUID poolEngineTxId);

    Page<Transaction> findByUserId(UUID userId, Pageable pageable);

    Page<Transaction> findByUserIdAndTxType(UUID userId, TransactionType txType, Pageable pageable);

    Page<Transaction> findByUserIdAndStatus(UUID userId, TransactionStatus status, Pageable pageable);

    Page<Transaction> findByUserIdAndTxTypeAndStatus(UUID userId, TransactionType txType, TransactionStatus status, Pageable pageable);

    @Query("SELECT t FROM Transaction t WHERE t.userId = :userId " +
           "AND (:txType IS NULL OR t.txType = :txType) " +
           "AND (:status IS NULL OR t.status = :status) " +
           "AND (:fromDate IS NULL OR t.createdAt >= :fromDate) " +
           "AND (:toDate IS NULL OR t.createdAt <= :toDate)")
    Page<Transaction> findFiltered(@Param("userId") UUID userId,
                                    @Param("txType") TransactionType txType,
                                    @Param("status") TransactionStatus status,
                                    @Param("fromDate") LocalDateTime fromDate,
                                    @Param("toDate") LocalDateTime toDate,
                                    Pageable pageable);

    @Query("SELECT t FROM Transaction t WHERE " +
           "(:txType IS NULL OR t.txType = :txType) " +
           "AND (:status IS NULL OR t.status = :status)")
    Page<Transaction> findAllFiltered(@Param("txType") TransactionType txType,
                                       @Param("status") TransactionStatus status,
                                       Pageable pageable);

    /**
     * Sprint 6 #6.9 — AML scanner input. All CONFIRMED transactions within
     * the time window across all users. Capped at {@link org.springframework.data.domain.Pageable}
     * caller-supplied page size to bound memory in case of unexpected
     * volume spike.
     */
    @Query("SELECT t FROM Transaction t " +
           "WHERE t.status = com.sber.dlmm.common.enums.TransactionStatus.CONFIRMED " +
           "AND t.createdAt >= :since " +
           "AND t.createdAt < :until " +
           "ORDER BY t.userId, t.createdAt ASC")
    java.util.List<Transaction> findConfirmedInWindow(@Param("since") LocalDateTime since,
                                                       @Param("until") LocalDateTime until,
                                                       Pageable pageable);
}
