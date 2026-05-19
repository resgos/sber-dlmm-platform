package com.sber.dlmm.token.repository;

import com.sber.dlmm.token.entity.YsrubYieldAccrual;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface YsrubYieldAccrualRepository extends JpaRepository<YsrubYieldAccrual, UUID> {

    /**
     * Used by the daily scheduler to ensure idempotency at the row level —
     * each (user, day) can only have one accrual. The unique constraint
     * at the DB level is the hard guarantee; this method gives a
     * graceful pre-check.
     */
    Optional<YsrubYieldAccrual> findByUserIdAndAccrualDay(UUID userId, LocalDate day);

    Page<YsrubYieldAccrual> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
