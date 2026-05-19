package com.sber.dlmm.token.repository;

import com.sber.dlmm.token.entity.YsrubReserveMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface YsrubReserveMovementRepository extends JpaRepository<YsrubReserveMovement, UUID> {

    /** Idempotency check — used by service to reject duplicate mint/burn calls. */
    Optional<YsrubReserveMovement> findByIdempotencyKey(String idempotencyKey);

    Page<YsrubReserveMovement> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /**
     * Total SRUB currently held in the reserve = sum of all DEPOSITS minus
     * sum of all WITHDRAWALS. Used by the daily reserve-health attestation
     * (Sprint 10 #7.6) to prove 1:1 backing.
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN m.direction = com.sber.dlmm.token.entity.YsrubReserveMovement.Direction.DEPOSIT THEN m.srubAmount ELSE -m.srubAmount END), 0) "
         + "FROM YsrubReserveMovement m")
    long totalReserveSrub();
}
