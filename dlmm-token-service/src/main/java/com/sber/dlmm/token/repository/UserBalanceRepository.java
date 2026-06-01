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

/**
 * Spring Data repository for the {@link UserBalance} aggregate (per-user,
 * per-token holdings keyed by {@link UserBalanceId}).
 *
 * <p>Two kinds of access:
 * <ul>
 *   <li>plain derived finders for reading a user's or a token's balances; and</li>
 *   <li>conditional {@code @Modifying} mutators (deduct / credit / lock /
 *       unlock / custody-fee mark) that move funds atomically in a single SQL
 *       UPDATE. The guards live in the WHERE clause (e.g.
 *       {@code available >= :amount}) so an overdraft simply updates 0 rows
 *       rather than producing a negative balance — callers MUST check the
 *       returned row count.</li>
 * </ul>
 * All balance amounts are raw integers at the uniform ×10⁴ platform scale
 * (1 unit = 10⁻⁴ token).
 */
@Repository
public interface UserBalanceRepository extends JpaRepository<UserBalance, UserBalanceId> {

    /**
     * Lists every token balance held by a user (the portfolio view).
     *
     * @param userId the owning user's id
     * @return the user's balance rows across all tokens (possibly empty)
     */
    List<UserBalance> findByUserId(UUID userId);

    /**
     * Fetches a single user's balance for one token by the full composite key.
     *
     * @param userId  the owning user's id
     * @param tokenId the token's catalog id
     * @return the balance row, or empty if the user holds no row for that token
     */
    Optional<UserBalance> findByUserIdAndTokenId(UUID userId, UUID tokenId);

    /**
     * Lists every holder's balance for a given token (the unpaginated form;
     * prefer the {@link #findByTokenId(UUID, Pageable) paginated} overload at
     * scale).
     *
     * @param tokenId the token's catalog id
     * @return all balance rows for that token (possibly empty)
     */
    List<UserBalance> findByTokenId(UUID tokenId);

    /** Paginated variant for the daily YSRUB yield sweep — avoids loading every
     *  holder into one heap-busting list at 100k-holder scale.
     *
     * @param tokenId  the token's catalog id
     * @param pageable page index, size and sort
     * @return one page of holder balances for that token */
    Page<UserBalance> findByTokenId(UUID tokenId, Pageable pageable);

    /**
     * Atomically debits {@code amount} from a user's {@code available}
     * balance, guarded against overdraft: the UPDATE only matches when
     * {@code available >= amount}, so an insufficient balance changes nothing.
     * Callers MUST treat a 0 return as "insufficient funds".
     *
     * @param userId  the owning user's id
     * @param tokenId the token's catalog id
     * @param amount  raw ×10⁴ amount to deduct
     * @return rows updated — 1 on success, 0 if the balance was insufficient
     *         or the row is absent
     */
    @Modifying
    @Query("UPDATE UserBalance b SET b.available = b.available - :amount, " +
           "b.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE b.userId = :userId AND b.tokenId = :tokenId " +
           "AND b.available >= :amount")
    int deductAvailable(@Param("userId") UUID userId,
                        @Param("tokenId") UUID tokenId,
                        @Param("amount") long amount);

    /**
     * Atomically credits {@code amount} to a user's {@code available}
     * balance. Unconditional on the amount (no upper bound), but still
     * requires the balance row to exist.
     *
     * @param userId  the owning user's id
     * @param tokenId the token's catalog id
     * @param amount  raw ×10⁴ amount to add
     * @return rows updated — 1 on success, 0 if no balance row exists
     */
    @Modifying
    @Query("UPDATE UserBalance b SET b.available = b.available + :amount, " +
           "b.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE b.userId = :userId AND b.tokenId = :tokenId")
    int creditAvailable(@Param("userId") UUID userId,
                        @Param("tokenId") UUID tokenId,
                        @Param("amount") long amount);

    /**
     * Atomically moves {@code amount} from {@code available} to {@code locked}
     * (reserves funds), guarded so it only succeeds when
     * {@code available >= amount}. The total holding is unchanged.
     *
     * @param userId  the owning user's id
     * @param tokenId the token's catalog id
     * @param amount  raw ×10⁴ amount to reserve
     * @return rows updated — 1 on success, 0 if available was insufficient
     */
    @Modifying
    @Query("UPDATE UserBalance b SET b.available = b.available - :amount, " +
           "b.locked = b.locked + :amount, b.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE b.userId = :userId AND b.tokenId = :tokenId " +
           "AND b.available >= :amount")
    int lockBalance(@Param("userId") UUID userId,
                    @Param("tokenId") UUID tokenId,
                    @Param("amount") long amount);

    /**
     * Atomically moves {@code amount} from {@code locked} back to
     * {@code available} (releases a reservation), guarded so it only succeeds
     * when {@code locked >= amount}. The total holding is unchanged.
     *
     * @param userId  the owning user's id
     * @param tokenId the token's catalog id
     * @param amount  raw ×10⁴ amount to release
     * @return rows updated — 1 on success, 0 if locked was insufficient
     */
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
     *
     * @param sinceCutoff accrue only rows last accrued strictly before this
     *                    instant (NULL last-accrual treated as the epoch)
     * @param limit       maximum rows to return in one tick
     * @return due balances, oldest-accrued first, at most {@code limit} rows
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
     *
     * @param userId  the owning user's id
     * @param tokenId the token's catalog id
     * @param now     timestamp to record as the last custody-fee accrual
     * @return rows updated — 1 on success, 0 if no balance row exists
     */
    @Modifying
    @Query("UPDATE UserBalance b SET b.lastCustodyFeeAt = :now, " +
           "b.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE b.userId = :userId AND b.tokenId = :tokenId")
    int markCustodyAccrued(@Param("userId") UUID userId,
                           @Param("tokenId") UUID tokenId,
                           @Param("now") java.time.LocalDateTime now);
}
