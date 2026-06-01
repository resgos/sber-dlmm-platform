package com.sber.dlmm.token.repository;

import com.sber.dlmm.token.entity.YsrubReserveMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link YsrubReserveMovement} (the append-only
 * YSRUB mint/burn reserve ledger).
 *
 * <p>Backs three needs: idempotent mint/burn (lookup by idempotency key),
 * the user-facing movement history, and the reserve-health attestation
 * (aggregate reserve total).
 */
@Repository
public interface YsrubReserveMovementRepository extends JpaRepository<YsrubReserveMovement, UUID> {

    /** Idempotency check — used by service to reject duplicate mint/burn calls.
     *
     * @param idempotencyKey the client-supplied dedup key (DB-unique)
     * @return the prior movement recorded under that key, or empty if first-seen */
    Optional<YsrubReserveMovement> findByIdempotencyKey(String idempotencyKey);

    /**
     * Returns one page of a user's reserve movements, most recently created
     * first (ordered by {@code createdAt} descending) — the YSRUB mint/burn
     * history view.
     *
     * @param userId   the user whose movements to list
     * @param pageable page index, size and sort
     * @return the requested page of movements, newest first
     */
    Page<YsrubReserveMovement> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /**
     * Total SRUB currently held in the reserve = sum of all DEPOSITS minus
     * sum of all WITHDRAWALS. Used by the daily reserve-health attestation
     * (Sprint 10 #7.6) to prove 1:1 backing.
     *
     * <p>Implementation note: native SQL because Hibernate's HQL parser
     * cannot resolve qualified inner-enum constants (the previous
     * {@code m.direction = ...YsrubReserveMovement.Direction.DEPOSIT}
     * form fails with SemanticException at repository init). The
     * direction column is persisted as its name() string via
     * {@code @Enumerated(EnumType.STRING)} so the SQL literal matches
     * exactly. Casting to bigint matches the {@code BIGINT srub_amount}
     * column type and the {@code long} return.
     *
     * @return net SRUB held in the reserve (sum of DEPOSITS minus WITHDRAWALS,
     *         raw ×10⁴ units), or 0 when there are no movements
     */
    @Query(value = "SELECT COALESCE(SUM(CASE WHEN direction = 'DEPOSIT' THEN srub_amount ELSE -srub_amount END), 0) "
                 + "FROM ysrub_reserve_movements",
           nativeQuery = true)
    long totalReserveSrub();
}
