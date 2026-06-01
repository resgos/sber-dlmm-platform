package com.sber.dlmm.token.repository;

import com.sber.dlmm.token.entity.YsrubYieldAccrual;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link YsrubYieldAccrual} (the per-(user, day)
 * YSRUB yield ledger).
 *
 * <p>Serves the daily yield scheduler (idempotent per-day pre-check) and the
 * user-facing accrual history listing.
 */
@Repository
public interface YsrubYieldAccrualRepository extends JpaRepository<YsrubYieldAccrual, UUID> {

    /**
     * Used by the daily scheduler to ensure idempotency at the row level —
     * each (user, day) can only have one accrual. The unique constraint
     * at the DB level is the hard guarantee; this method gives a
     * graceful pre-check.
     *
     * @param userId the user whose accrual is sought
     * @param day    the accrual day to check
     * @return the existing accrual for that (user, day), or empty if not yet accrued
     */
    Optional<YsrubYieldAccrual> findByUserIdAndAccrualDay(UUID userId, LocalDate day);

    /**
     * Returns one page of a user's yield accruals, most recently created
     * first (ordered by {@code createdAt} descending) — the YSRUB yield
     * history view.
     *
     * @param userId   the user whose accruals to list
     * @param pageable page index, size and sort
     * @return the requested page of accruals, newest first
     */
    Page<YsrubYieldAccrual> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
