package com.sber.dlmm.token.repository;

import com.sber.dlmm.token.entity.UserBalance;
import com.sber.dlmm.token.entity.UserBalanceId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserBalanceRepository extends JpaRepository<UserBalance, UserBalanceId> {

    List<UserBalance> findByUserId(UUID userId);

    Optional<UserBalance> findByUserIdAndTokenId(UUID userId, UUID tokenId);

    List<UserBalance> findByTokenId(UUID tokenId);

    /** Paginated variant for the daily YSRUB yield sweep — avoids loading every
     *  holder into one heap-busting list at 100k-holder scale. */
    Page<UserBalance> findByTokenId(UUID tokenId, Pageable pageable);

    @Modifying
    @Query("UPDATE UserBalance b SET b.available = b.available - :amount, " +
           "b.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE b.userId = :userId AND b.tokenId = :tokenId " +
           "AND b.available >= :amount")
    int deductAvailable(@Param("userId") UUID userId,
                        @Param("tokenId") UUID tokenId,
                        @Param("amount") long amount);

    @Modifying
    @Query("UPDATE UserBalance b SET b.available = b.available + :amount, " +
           "b.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE b.userId = :userId AND b.tokenId = :tokenId")
    int creditAvailable(@Param("userId") UUID userId,
                        @Param("tokenId") UUID tokenId,
                        @Param("amount") long amount);

    @Modifying
    @Query("UPDATE UserBalance b SET b.available = b.available - :amount, " +
           "b.locked = b.locked + :amount, b.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE b.userId = :userId AND b.tokenId = :tokenId " +
           "AND b.available >= :amount")
    int lockBalance(@Param("userId") UUID userId,
                    @Param("tokenId") UUID tokenId,
                    @Param("amount") long amount);

    @Modifying
    @Query("UPDATE UserBalance b SET b.locked = b.locked - :amount, " +
           "b.available = b.available + :amount, b.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE b.userId = :userId AND b.tokenId = :tokenId " +
           "AND b.locked >= :amount")
    int unlockBalance(@Param("userId") UUID userId,
                      @Param("tokenId") UUID tokenId,
                      @Param("amount") long amount);

    /**
     * Rows due for custody-fee accrual: positive balance + either never
     * accrued before, OR last accrued before today. Uses COALESCE so
     * legacy rows (lastCustodyFeeAt = null) fall through naturally.
     * Capped at :limit so a single tick doesn't tie the DB for minutes
     * if the catalog grows past 100k balances.
     */
    // Native query: COALESCE with a CAST literal rather than the `::timestamp`
    // shorthand — Hibernate's parameter parser treats `:timestamp` as a
    // named-parameter placeholder and chokes on the postgres cast syntax.
    @Query(value =
        "SELECT * FROM user_balances " +
        "WHERE available > 0 " +
        "  AND COALESCE(last_custody_fee_at, CAST('1970-01-01' AS TIMESTAMP)) < :sinceCutoff " +
        "ORDER BY COALESCE(last_custody_fee_at, CAST('1970-01-01' AS TIMESTAMP)) ASC " +
        "LIMIT :limit",
        nativeQuery = true)
    List<UserBalance> findDueForCustodyFee(@Param("sinceCutoff") java.time.LocalDateTime sinceCutoff,
                                            @Param("limit") int limit);

    /**
     * Dedicated UPDATE for the accrual mark. We CANNOT use save() on the
     * UserBalance entity because the in-memory copy still holds the
     * pre-deduct `available` — JPA save() writes all fields and would
     * undo the {@link #deductAvailable} we just ran. This @Modifying
     * query touches only the two columns that legitimately changed.
     */
    @Modifying
    @Query("UPDATE UserBalance b SET b.lastCustodyFeeAt = :now, " +
           "b.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE b.userId = :userId AND b.tokenId = :tokenId")
    int markCustodyAccrued(@Param("userId") UUID userId,
                           @Param("tokenId") UUID tokenId,
                           @Param("now") java.time.LocalDateTime now);
}
