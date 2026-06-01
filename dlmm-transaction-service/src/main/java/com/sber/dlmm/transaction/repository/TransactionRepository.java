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

/**
 * Spring Data JPA repository for {@link Transaction} ledger rows.
 *
 * <p>Provides the two idempotency lookups used to deduplicate writes
 * ({@code idempotencyKey} and {@code poolEngineTxId}), the user-scoped
 * paged history queries that back the user-ui transaction list (with
 * optional type/status/date filters), the admin-wide filtered query,
 * and two specialised feeds: the AML scanner's confirmed-in-window
 * scan and the pool-scoped recent-swap feed for the PoolDetailPage.
 */
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /**
     * Looks up a transaction by its client-supplied idempotency token.
     *
     * @param idempotencyKey the unique key the caller stamped on the request
     * @return the matching transaction, or empty if none was recorded under that key
     */
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    /**
     * Sprint 9-DS-r4 (P0-4) — second dedup key for the swap event
     * consumer. The {@code SwapExecuted} envelope always carries the
     * pool-engine row UUID; this lookup lets us recognise a
     * re-delivered event even when the originating swap had no
     * client-supplied idempotencyKey.
     *
     * @param poolEngineTxId the pool-engine swap row UUID carried by the {@code SwapExecuted} event
     * @return the transaction already persisted for that swap, or empty if not yet consumed
     */
    Optional<Transaction> findByPoolEngineTxId(UUID poolEngineTxId);

    /**
     * Pages all of one user's transactions (no type/status filter).
     *
     * @param userId   owner whose transactions to return
     * @param pageable page number, size and sort
     * @return a page of the user's transactions in the requested order
     */
    Page<Transaction> findByUserId(UUID userId, Pageable pageable);

    /**
     * Pages one user's transactions of a single type.
     *
     * @param userId   owner whose transactions to return
     * @param txType   transaction type to filter on
     * @param pageable page number, size and sort
     * @return a page of the user's transactions of that type
     */
    Page<Transaction> findByUserIdAndTxType(UUID userId, TransactionType txType, Pageable pageable);

    /**
     * Pages one user's transactions in a single status.
     *
     * @param userId   owner whose transactions to return
     * @param status   lifecycle status to filter on
     * @param pageable page number, size and sort
     * @return a page of the user's transactions in that status
     */
    Page<Transaction> findByUserIdAndStatus(UUID userId, TransactionStatus status, Pageable pageable);

    /**
     * Pages one user's transactions matching both a type and a status.
     *
     * @param userId   owner whose transactions to return
     * @param txType   transaction type to filter on
     * @param status   lifecycle status to filter on
     * @param pageable page number, size and sort
     * @return a page of the user's transactions matching both filters
     */
    Page<Transaction> findByUserIdAndTxTypeAndStatus(UUID userId, TransactionType txType, TransactionStatus status, Pageable pageable);

    /**
     * Pages one user's transactions with every filter optional: a null
     * {@code txType}, {@code status}, {@code fromDate} or {@code toDate}
     * disables that predicate. Backs the user-ui history filter bar.
     *
     * @param userId   owner whose transactions to return (required)
     * @param txType   transaction type filter, or null for any type
     * @param status   lifecycle status filter, or null for any status
     * @param fromDate inclusive lower bound on {@code createdAt}, or null for unbounded
     * @param toDate   inclusive upper bound on {@code createdAt}, or null for unbounded
     * @param pageable page number, size and sort
     * @return a page of the user's matching transactions
     */
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

    /**
     * Admin-wide variant of {@link #findFiltered}: pages transactions
     * across all users, with type and status both optional (null
     * disables that predicate).
     *
     * @param txType   transaction type filter, or null for any type
     * @param status   lifecycle status filter, or null for any status
     * @param pageable page number, size and sort
     * @return a page of matching transactions across all users
     */
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
     * volume spike. Ordered by user then ascending {@code createdAt} so the
     * scanner can walk each user's activity chronologically.
     *
     * @param since    inclusive lower bound on {@code createdAt}
     * @param until    exclusive upper bound on {@code createdAt}
     * @param pageable page size cap (and any further sort) for the scan
     * @return CONFIRMED transactions in the window, grouped by user and time-ordered
     */
    @Query("SELECT t FROM Transaction t " +
           "WHERE t.status = com.sber.dlmm.common.enums.TransactionStatus.CONFIRMED " +
           "AND t.createdAt >= :since " +
           "AND t.createdAt < :until " +
           "ORDER BY t.userId, t.createdAt ASC")
    java.util.List<Transaction> findConfirmedInWindow(@Param("since") LocalDateTime since,
                                                       @Param("until") LocalDateTime until,
                                                       Pageable pageable);

    /**
     * Sprint 9-DS-r4 (P1-6) — pool-scoped recent SWAP feed for the
     * user-ui PoolDetailPage "История" panel below the bin chart
     * (Meteora-style). CONFIRMED only so the feed never flashes a
     * pending tx that later fails. Backed by the existing
     * idx_transactions_pool_id index. Newest first.
     *
     * @param poolId   pool whose recent swaps to return
     * @param pageable how many recent swaps to return (and any further sort)
     * @return CONFIRMED SWAP transactions for the pool, newest first
     */
    @Query("SELECT t FROM Transaction t " +
           "WHERE t.poolId = :poolId " +
           "AND t.txType = com.sber.dlmm.common.enums.TransactionType.SWAP " +
           "AND t.status = com.sber.dlmm.common.enums.TransactionStatus.CONFIRMED " +
           "ORDER BY t.createdAt DESC")
    java.util.List<Transaction> findRecentPoolSwaps(@Param("poolId") UUID poolId, Pageable pageable);
}
