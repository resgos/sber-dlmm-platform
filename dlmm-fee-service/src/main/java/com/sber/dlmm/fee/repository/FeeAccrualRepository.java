package com.sber.dlmm.fee.repository;

import com.sber.dlmm.fee.entity.FeeAccrual;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository over {@link FeeAccrual} rows (the per-token,
 * per-position fee-accrual ledger). Provides the lookups the fee summary,
 * history and claim flows need — by user, position, or pool, and filtered to
 * unclaimed accruals. Derived-query methods only; no ordering is imposed unless
 * a {@link Pageable} {@code Sort} is supplied by the caller.
 */
@Repository
public interface FeeAccrualRepository extends JpaRepository<FeeAccrual, UUID> {

    /**
     * All accruals owned by a user, claimed and unclaimed, across every pool.
     *
     * @param userId owner of the positions
     * @return every accrual row for the user (unordered)
     */
    List<FeeAccrual> findByUserId(UUID userId);

    /**
     * All accruals for a single position, claimed and unclaimed.
     *
     * @param positionId the LP position
     * @return every accrual row for the position (unordered)
     */
    List<FeeAccrual> findByPositionId(UUID positionId);

    /**
     * All accruals a user earned in one specific pool.
     *
     * @param poolId the pool to filter by
     * @param userId owner of the positions
     * @return matching accrual rows (unordered)
     */
    List<FeeAccrual> findByPoolIdAndUserId(UUID poolId, UUID userId);

    /**
     * A user's still-unclaimed accruals only (those with {@code claimed = false}) —
     * the balance available to claim.
     *
     * @param userId owner of the positions
     * @return unclaimed accrual rows for the user (unordered)
     */
    List<FeeAccrual> findByUserIdAndClaimedFalse(UUID userId);

    /**
     * Page over all of a user's accruals (e.g. the fee-history endpoint).
     *
     * @param userId   owner of the positions
     * @param pageable page index, size and sort (ordering comes from here)
     * @return the requested page of accrual rows
     */
    Page<FeeAccrual> findByUserId(UUID userId, Pageable pageable);

    /**
     * Page over a user's accruals scoped to a single pool.
     *
     * @param userId   owner of the positions
     * @param poolId   the pool to filter by
     * @param pageable page index, size and sort (ordering comes from here)
     * @return the requested page of accrual rows for that pool
     */
    Page<FeeAccrual> findByUserIdAndPoolId(UUID userId, UUID poolId, Pageable pageable);
}
