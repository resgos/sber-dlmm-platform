package com.sber.dlmm.transaction.repository;

import com.sber.dlmm.transaction.entity.OtcBlockTrade;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link OtcBlockTrade} workflow records.
 *
 * <p>Backs the OTC desk: a newest-first admin dashboard, per-user inbox
 * (counterparty) and outbox (initiator) drill-downs, a status-filtered
 * view, and the scheduler query that finds expired quotes to sweep.
 */
@Repository
public interface OtcBlockTradeRepository extends JpaRepository<OtcBlockTrade, UUID> {

    /**
     * Admin "all open trades" dashboard view, newest first.
     *
     * @param pageable page number, size and any further sort
     * @return a page of all block trades ordered by {@code createdAt} descending
     */
    Page<OtcBlockTrade> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Drill-down — the counterparty's inbox, newest first.
     *
     * @param counterpartyUserId user on the receiving (taker) side
     * @param pageable           page number, size and any further sort
     * @return a page of trades addressed to that counterparty, newest first
     */
    Page<OtcBlockTrade> findByCounterpartyUserIdOrderByCreatedAtDesc(UUID counterpartyUserId, Pageable pageable);

    /**
     * Drill-down — the initiator's outbox, newest first.
     *
     * @param initiatorUserId user on the originating (maker) side
     * @param pageable        page number, size and any further sort
     * @return a page of trades raised by that initiator, newest first
     */
    Page<OtcBlockTrade> findByInitiatorUserIdOrderByCreatedAtDesc(UUID initiatorUserId, Pageable pageable);

    /**
     * Status-filtered view ("all QUOTED awaiting acceptance", etc.), newest first.
     *
     * @param status   workflow state to filter on
     * @param pageable page number, size and any further sort
     * @return a page of trades in that status, newest first
     */
    Page<OtcBlockTrade> findByStatusOrderByCreatedAtDesc(OtcBlockTrade.Status status, Pageable pageable);

    /**
     * Scheduler input — quotes past their expiry that haven't been ACCEPTED.
     * Sprint 9 #6.1 ships without auto-expiry; Sprint 10 adds a scheduled
     * tick that transitions QUOTED → EXPIRED when quoteExpiresAt passes.
     *
     * @param status status to match (the sweep passes QUOTED)
     * @param cutoff the sweep "now"; matches rows whose {@code quoteExpiresAt} is strictly before it
     * @return trades in {@code status} whose quote already expired
     */
    List<OtcBlockTrade> findByStatusAndQuoteExpiresAtBefore(
            OtcBlockTrade.Status status, java.time.LocalDateTime cutoff);
}
