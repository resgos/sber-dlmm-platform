package com.sber.dlmm.transaction.service;

import com.sber.dlmm.transaction.entity.OtcBlockTrade;
import com.sber.dlmm.transaction.repository.OtcBlockTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Sprint 9 #6.1 (M#7) — OTC desk workflow service.
 *
 * <p>State-machine driver for {@link OtcBlockTrade}. Each transition is
 * a public method with its own preconditions; invalid transitions
 * throw {@link IllegalStateException} so admin UI surfaces the error
 * loudly rather than silently corrupting state.
 *
 * <p>State diagram (legal transitions):
 * <pre>
 *   REQUESTED ──quote──→ QUOTED ──accept──→ ACCEPTED ──settle──→ SETTLED  (terminal)
 *       │                  │                      │
 *       └──cancel──→ CANCELLED                    │
 *                          ├──reject──→ REJECTED  │  (terminal)
 *                          ├──expire──→ EXPIRED   │  (terminal)
 *                          └──cancel──→ CANCELLED ┘
 * </pre>
 *
 * <p>Sprint 9 MVP scope:
 * <ul>
 *   <li>Admin operator drives all transitions (no counterparty self-service)</li>
 *   <li>Settlement records the txId reference but the actual ledger
 *       write happens out-of-band (operator-driven manual settlement
 *       in Sprint 9; Sprint 10 wires automatic swap execution via
 *       pool-engine /pools/swap)</li>
 *   <li>No auto-expiry — Sprint 10 adds @Scheduled tick</li>
 * </ul>
 *
 * <p>Audit: all transition methods are @AdminAudit-annotated on the
 * controller layer (admin_audit_log captures actor + before/after).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OtcDeskService {

    private static final Set<OtcBlockTrade.Status> TERMINAL = EnumSet.of(
            OtcBlockTrade.Status.SETTLED,
            OtcBlockTrade.Status.REJECTED,
            OtcBlockTrade.Status.EXPIRED,
            OtcBlockTrade.Status.CANCELLED
    );

    private final OtcBlockTradeRepository repository;

    /**
     * Create a new block trade in REQUESTED state. Admin operator sets
     * initiator, counterparty, token pair, and nominal in.
     *
     * <p>Rejects self-trades and same-token pairs up front so an obviously
     * invalid trade never reaches the state machine.
     *
     * @param initiatorUserId    user requesting the block trade
     * @param counterpartyUserId the other side (must differ from initiator)
     * @param tokenInId          token the initiator offers (must differ from out)
     * @param tokenOutId         token the initiator wants
     * @param amountIn           nominal amount in, raw units (must be &gt; 0)
     * @param notes              optional free-text operator notes
     * @param createdByAdminId   admin operator recorded as creator
     * @return the persisted trade in {@link OtcBlockTrade.Status#REQUESTED}
     * @throws IllegalArgumentException if {@code amountIn} ≤ 0, initiator equals
     *         counterparty, or token-in equals token-out
     */
    @Transactional
    public OtcBlockTrade create(UUID initiatorUserId, UUID counterpartyUserId,
                                 UUID tokenInId, UUID tokenOutId, long amountIn,
                                 String notes, UUID createdByAdminId) {
        validatePositive(amountIn, "amountIn");
        if (initiatorUserId.equals(counterpartyUserId)) {
            throw new IllegalArgumentException("Initiator and counterparty must differ");
        }
        if (tokenInId.equals(tokenOutId)) {
            throw new IllegalArgumentException("Token in and out must differ");
        }

        OtcBlockTrade trade = OtcBlockTrade.builder()
                .initiatorUserId(initiatorUserId)
                .counterpartyUserId(counterpartyUserId)
                .createdByAdminId(createdByAdminId)
                .tokenInId(tokenInId)
                .tokenOutId(tokenOutId)
                .amountIn(amountIn)
                .status(OtcBlockTrade.Status.REQUESTED)
                .notes(notes)
                .build();
        trade = repository.save(trade);
        log.info("OTC trade created: id={} initiator={} counterparty={} amountIn={}",
                trade.getId(), initiatorUserId, counterpartyUserId, amountIn);
        return trade;
    }

    /**
     * REQUESTED → QUOTED. Admin attaches a price + expiry window.
     *
     * @param tradeId          id of the REQUESTED trade to quote
     * @param amountOut        quoted amount out, raw units (must be &gt; 0)
     * @param quotedPriceMicro quoted price in micro-units (must be &gt; 0)
     * @param quoteExpiresAt   instant the quote lapses (must be in the future)
     * @return the persisted trade now in {@link OtcBlockTrade.Status#QUOTED}
     * @throws IllegalArgumentException if amounts ≤ 0 or {@code quoteExpiresAt}
     *         is null/in the past
     * @throws IllegalStateException    if the trade is not in REQUESTED state
     */
    @Transactional
    public OtcBlockTrade quote(UUID tradeId, long amountOut, long quotedPriceMicro,
                                LocalDateTime quoteExpiresAt) {
        validatePositive(amountOut, "amountOut");
        validatePositive(quotedPriceMicro, "quotedPriceMicro");
        if (quoteExpiresAt == null || quoteExpiresAt.isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("quoteExpiresAt must be in the future");
        }
        OtcBlockTrade trade = find(tradeId);
        requireStatus(trade, OtcBlockTrade.Status.REQUESTED);
        trade.setStatus(OtcBlockTrade.Status.QUOTED);
        trade.setAmountOut(amountOut);
        trade.setQuotedPriceMicro(quotedPriceMicro);
        trade.setQuotedAt(LocalDateTime.now());
        trade.setQuoteExpiresAt(quoteExpiresAt);
        log.info("OTC trade quoted: id={} amountOut={} price={} expires={}",
                tradeId, amountOut, quotedPriceMicro, quoteExpiresAt);
        return repository.save(trade);
    }

    /**
     * QUOTED → ACCEPTED. Counterparty (proxied by admin in Sprint 9) accepts.
     * Re-checks quote expiry at accept-time so a stale quote can't be honoured.
     *
     * @param tradeId id of the QUOTED trade to accept
     * @return the persisted trade now in {@link OtcBlockTrade.Status#ACCEPTED}
     * @throws IllegalStateException if the trade is not QUOTED, or the quote
     *         has already expired
     */
    @Transactional
    public OtcBlockTrade accept(UUID tradeId) {
        OtcBlockTrade trade = find(tradeId);
        requireStatus(trade, OtcBlockTrade.Status.QUOTED);
        if (trade.getQuoteExpiresAt() != null
                && trade.getQuoteExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalStateException("Quote expired at " + trade.getQuoteExpiresAt()
                    + "; cannot accept");
        }
        trade.setStatus(OtcBlockTrade.Status.ACCEPTED);
        log.info("OTC trade accepted: id={}", tradeId);
        return repository.save(trade);
    }

    /**
     * QUOTED → REJECTED. Counterparty (proxied by admin) declines. A non-blank
     * reason is appended to the trade notes for the audit trail.
     *
     * @param tradeId id of the QUOTED trade to reject
     * @param reason  optional decline reason appended to notes, or {@code null}
     * @return the persisted trade now in {@link OtcBlockTrade.Status#REJECTED}
     * @throws IllegalStateException if the trade is not in QUOTED state
     */
    @Transactional
    public OtcBlockTrade reject(UUID tradeId, String reason) {
        OtcBlockTrade trade = find(tradeId);
        requireStatus(trade, OtcBlockTrade.Status.QUOTED);
        trade.setStatus(OtcBlockTrade.Status.REJECTED);
        if (reason != null && !reason.isBlank()) {
            trade.setNotes(appendNote(trade.getNotes(), "REJECTED: " + reason));
        }
        log.info("OTC trade rejected: id={} reason={}", tradeId, reason);
        return repository.save(trade);
    }

    /**
     * ACCEPTED → SETTLED. Records the settlement transaction reference.
     * The actual swap/transfer happens out-of-band in Sprint 9 (manual
     * operator action); Sprint 10 auto-wires to pool-engine /swap.
     *
     * @param tradeId        id of the ACCEPTED trade to settle
     * @param settlementTxId reference to the ledger transaction that executed
     *                       the transfer (required)
     * @return the persisted trade now in {@link OtcBlockTrade.Status#SETTLED}
     * @throws IllegalArgumentException if {@code settlementTxId} is null
     * @throws IllegalStateException    if the trade is not in ACCEPTED state
     */
    @Transactional
    public OtcBlockTrade settle(UUID tradeId, UUID settlementTxId) {
        if (settlementTxId == null) {
            throw new IllegalArgumentException("settlementTxId required");
        }
        OtcBlockTrade trade = find(tradeId);
        requireStatus(trade, OtcBlockTrade.Status.ACCEPTED);
        trade.setStatus(OtcBlockTrade.Status.SETTLED);
        trade.setSettlementTxId(settlementTxId);
        trade.setSettledAt(LocalDateTime.now());
        log.info("OTC trade settled: id={} txId={}", tradeId, settlementTxId);
        return repository.save(trade);
    }

    /**
     * REQUESTED | QUOTED → CANCELLED. Admin cancels the trade.
     * ACCEPTED can't be cancelled (must settle or be operator-reversed
     * via separate compensating action).
     *
     * @param tradeId id of the REQUESTED or QUOTED trade to cancel
     * @param reason  optional cancel reason appended to notes, or {@code null}
     * @return the persisted trade now in {@link OtcBlockTrade.Status#CANCELLED}
     * @throws IllegalStateException if the trade is not REQUESTED or QUOTED
     */
    @Transactional
    public OtcBlockTrade cancel(UUID tradeId, String reason) {
        OtcBlockTrade trade = find(tradeId);
        if (trade.getStatus() != OtcBlockTrade.Status.REQUESTED
                && trade.getStatus() != OtcBlockTrade.Status.QUOTED) {
            throw new IllegalStateException("Cannot cancel from status " + trade.getStatus()
                    + " — only REQUESTED or QUOTED");
        }
        trade.setStatus(OtcBlockTrade.Status.CANCELLED);
        if (reason != null && !reason.isBlank()) {
            trade.setNotes(appendNote(trade.getNotes(), "CANCELLED: " + reason));
        }
        log.info("OTC trade cancelled: id={} reason={}", tradeId, reason);
        return repository.save(trade);
    }

    // ── helpers ──

    /**
     * Loads a trade by id or fails loudly — every transition starts here.
     *
     * @param tradeId id to load
     * @return the trade entity
     * @throws IllegalArgumentException if no trade with {@code tradeId} exists
     */
    private OtcBlockTrade find(UUID tradeId) {
        return repository.findById(tradeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "OTC block trade not found: " + tradeId));
    }

    /**
     * Guards a transition's precondition: the trade must currently be in the
     * expected source state.
     *
     * @param trade    trade to check
     * @param expected status the trade must be in for the transition to proceed
     * @throws IllegalStateException if the trade's status is not {@code expected}
     */
    private void requireStatus(OtcBlockTrade trade, OtcBlockTrade.Status expected) {
        if (trade.getStatus() != expected) {
            throw new IllegalStateException("OTC trade " + trade.getId()
                    + " is " + trade.getStatus() + ", required " + expected);
        }
    }

    /**
     * Validates that a money/quantity field is strictly positive.
     *
     * @param value field value to check
     * @param field field name, used in the exception message
     * @throws IllegalArgumentException if {@code value} ≤ 0
     */
    private static void validatePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be > 0 (was " + value + ")");
        }
    }

    /**
     * Appends an audit note to the existing notes, " | "-separated, treating
     * a blank existing value as empty.
     *
     * @param existing current notes (may be {@code null}/blank)
     * @param addition note to append
     * @return the combined note string
     */
    private static String appendNote(String existing, String addition) {
        if (existing == null || existing.isBlank()) return addition;
        return existing + " | " + addition;
    }

    /**
     * True when status is terminal (no further transitions allowed).
     *
     * @param status status to test
     * @return {@code true} for SETTLED / REJECTED / EXPIRED / CANCELLED
     */
    public static boolean isTerminal(OtcBlockTrade.Status status) {
        return TERMINAL.contains(status);
    }
}
