package com.sber.dlmm.transaction.repository;

import com.sber.dlmm.transaction.entity.OtcBlockTrade;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OtcBlockTradeRepository extends JpaRepository<OtcBlockTrade, UUID> {

    /** Admin "all open trades" dashboard view. */
    Page<OtcBlockTrade> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Drill-down — counterparty's inbox. */
    Page<OtcBlockTrade> findByCounterpartyUserIdOrderByCreatedAtDesc(UUID counterpartyUserId, Pageable pageable);

    /** Drill-down — initiator's outbox. */
    Page<OtcBlockTrade> findByInitiatorUserIdOrderByCreatedAtDesc(UUID initiatorUserId, Pageable pageable);

    /** Status-filtered view ("all QUOTED awaiting acceptance", etc.). */
    Page<OtcBlockTrade> findByStatusOrderByCreatedAtDesc(OtcBlockTrade.Status status, Pageable pageable);

    /**
     * Scheduler input — quotes past their expiry that haven't been ACCEPTED.
     * Sprint 9 #6.1 ships without auto-expiry; Sprint 10 adds a scheduled
     * tick that transitions QUOTED → EXPIRED when quoteExpiresAt passes.
     */
    List<OtcBlockTrade> findByStatusAndQuoteExpiresAtBefore(
            OtcBlockTrade.Status status, java.time.LocalDateTime cutoff);
}
