package com.sber.dlmm.transaction.service;

import com.sber.dlmm.common.dto.PageResponse;
import com.sber.dlmm.common.enums.B2BSettlementStatus;
import com.sber.dlmm.common.exception.B2BSettlementValidationException;
import com.sber.dlmm.common.exception.InsufficientBalanceException;
import com.sber.dlmm.common.exception.TransactionFailedException;
import com.sber.dlmm.transaction.client.TokenServiceClient;
import com.sber.dlmm.transaction.dto.B2BSettlementRequest;
import com.sber.dlmm.transaction.dto.B2BSettlementResponse;
import com.sber.dlmm.transaction.entity.B2BSettlement;
import com.sber.dlmm.transaction.repository.B2BSettlementRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 4 #4.6 — corp-to-corp same-token transfer routed through DLMM
 * token rails. Prototype scope:
 * <ul>
 *   <li>Idempotent by {@code reference} — duplicate POST returns existing row.</li>
 *   <li>Two-phase execution: insert PENDING audit row in its own short
 *       transaction (committed immediately so polling sees it), then run
 *       deduct → credit OUTSIDE the JPA transaction, finally flip status
 *       in another short transaction.</li>
 *   <li>On clean success → COMPLETED + {@code completed_at} stamp.</li>
 *   <li>On deduct failure (insufficient balance) → FAILED, no debit happened,
 *       safe to retry with a new reference once funds are present.</li>
 *   <li>On credit failure AFTER deduct succeeded → FAILED with a LOUD
 *       error_message tagged [RECONCILE]. Saga / compensating deduct-undo
 *       is Sprint 5+ work — documented in the FAILED enum javadoc.</li>
 * </ul>
 *
 * <p>Uses {@link TransactionTemplate} (programmatic) rather than {@code @Transactional}
 * for the per-step commits because self-invocation from {@link #submit} to a
 * private helper would bypass the Spring AOP proxy entirely — the annotation
 * would be silently ignored. The programmatic API gives the boundary directly.
 *
 * <p>NOT implemented (deliberate prototype scope):
 * <ul>
 *   <li>KYB role distinct from KYC — current endpoint is ADMIN-gated.</li>
 *   <li>Kafka event publication (corp ERP polls; downstream consumers don't exist yet).</li>
 *   <li>CSV report for settlements — parallel to #4.4, separate ticket.</li>
 *   <li>FX-conversion settlement — that's a swap, lives in regular transactions.</li>
 * </ul>
 */
@Service
@Slf4j
public class B2BSettlementService {

    private final B2BSettlementRepository repository;
    private final TokenServiceClient tokenClient;
    private final TransactionTemplate txTemplate;

    /**
     * Sprint 5 #5.12 — fee in basis points charged ON the transferred
     * amount. Default 5 bps (= 0.05%) per Sprint 4 #4.6 kickoff
     * commercial promise.
     */
    @Value("${dlmm.b2b.fee-bps:5}")
    private int feeBps;

    /**
     * Sprint 5 #5.12 — НДС rate (percent). Default 20 (стандартная ставка).
     * Льготная 10% / 0% reserved for future commodity-class expansion that
     * doesn't apply to current DLMM-served operations.
     */
    @Value("${dlmm.b2b.vat-rate-pct:20}")
    private short vatRatePct;

    /**
     * Builds the service and pre-configures a REQUIRES_NEW
     * {@link TransactionTemplate}. The propagation choice is deliberate:
     * each PENDING / COMPLETED / FAILED commit must land in its own short
     * transaction so a poller can observe the PENDING row even though the
     * deduct/credit work runs outside any JPA transaction (see class doc).
     *
     * @param repository  B2B settlement persistence
     * @param tokenClient client used to deduct/credit token balances
     * @param txManager   platform transaction manager backing the template
     */
    public B2BSettlementService(B2BSettlementRepository repository,
                                TokenServiceClient tokenClient,
                                PlatformTransactionManager txManager) {
        this.repository = repository;
        this.tokenClient = tokenClient;
        this.txTemplate = new TransactionTemplate(txManager);
        // Each commit is its own short txn — PENDING must be visible to
        // pollers even if a later COMPLETED/FAILED commit happens in a
        // separate session, so we don't want any enclosing context.
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Sprint 5 #5.12 — fee + НДС split. Russian B2B "fee includes НДС"
     * convention: customer sees and pays {@code grossFeeAmount}; we split
     * that gross into НДС (must remit to ФНС) and net (DLMM revenue).
     *
     * <p>Formula:
     * <pre>
     *   gross = floor(amount × feeBps / 10000)
     *   vat   = floor(gross × vatRatePct / (100 + vatRatePct))
     *   net   = gross - vat
     * </pre>
     *
     * <p>floor on each step is intentional — accountant prefers
     * "we collected exactly N kopecks" over "rounding sometimes adds 0.01"
     * because every rounding-up requires a corrective ФНС entry.
     *
     * <p>Package-private static so unit tests can hit it directly without
     * Spring wiring. Returns a record with the three numbers; caller
     * persists them into the entity.
     *
     * @param amount     principal the fee is charged on, raw units
     * @param feeBps     fee rate in basis points
     * @param vatRatePct НДС rate as a whole percent (e.g. 20)
     * @return a {@link FeeSplit} of (gross, vat, net); all-zero when
     *         {@code amount} ≤ 0 or {@code feeBps} ≤ 0
     */
    static FeeSplit computeFeeSplit(long amount, int feeBps, short vatRatePct) {
        if (amount <= 0 || feeBps <= 0) return new FeeSplit(0, 0, 0);
        long gross = amount * feeBps / 10_000L;
        long vat = gross * vatRatePct / (100L + vatRatePct);
        long net = gross - vat;
        return new FeeSplit(gross, vat, net);
    }

    /**
     * Immutable result of {@link #computeFeeSplit}: the customer-visible
     * {@code gross} fee split into {@code vat} (remittable to ФНС) and
     * {@code net} (DLMM revenue), all in raw units.
     *
     * @param gross total fee charged to the customer
     * @param vat   НДС portion of the gross fee
     * @param net   net revenue portion (gross − vat)
     */
    record FeeSplit(long gross, long vat, long net) {}

    /**
     * Idempotent submit. Returns the final state after the deduct/credit
     * attempt — a clean run returns COMPLETED, a deduct-rejection returns
     * FAILED with [CLEAN] error_message, a credit-after-deduct failure
     * returns FAILED with [RECONCILE] error_message.
     *
     * <p>Flow: reject self-transfers, short-circuit on a duplicate
     * {@code reference}, compute the fee split, commit a PENDING audit row in
     * its own transaction, then hand off to {@link #execute} for the money
     * movement.
     *
     * @param req       settlement request (counterparty, token, amount,
     *                  reference, notes)
     * @param initiator JWT subject performing the debit side
     * @return the resulting settlement state as a response DTO
     * @throws B2BSettlementValidationException if the counterparty equals the
     *         initiator
     */
    public B2BSettlementResponse submit(B2BSettlementRequest req, UUID initiator) {
        if (initiator.equals(req.counterpartyUserId())) {
            throw new B2BSettlementValidationException(
                    "Counterparty cannot equal initiator (would be a no-op self-transfer)");
        }

        // Idempotency: same reference returns existing row, whatever its current status.
        var existing = repository.findByReference(req.reference());
        if (existing.isPresent()) {
            log.info("B2B settlement reference={} already exists id={} status={}, returning existing",
                    req.reference(), existing.get().getId(), existing.get().getStatus());
            return B2BSettlementResponse.from(existing.get());
        }

        // Sprint 5 #5.12 — compute fee split before persisting.
        FeeSplit split = computeFeeSplit(req.amount(), feeBps, vatRatePct);

        B2BSettlement pending = txTemplate.execute(status -> {
            B2BSettlement row = B2BSettlement.builder()
                    .fromUserId(initiator)
                    .toUserId(req.counterpartyUserId())
                    .tokenId(req.tokenId())
                    .amount(req.amount())
                    .reference(req.reference())
                    .status(B2BSettlementStatus.PENDING)
                    .notes(req.notes())
                    .requestedBy(initiator)
                    .grossFeeAmount(split.gross())
                    .vatAmount(split.vat())
                    .netFeeAmount(split.net())
                    .vatRatePct(vatRatePct)
                    .build();
            return repository.save(row);
        });

        log.info("B2B settlement PENDING id={} reference={} from={} to={} token={} amount={} fee={}/НДС{}/net{}",
                pending.getId(), req.reference(), initiator, req.counterpartyUserId(),
                req.tokenId(), req.amount(), split.gross(), split.vat(), split.net());

        return execute(pending);
    }

    /**
     * Deduct → credit, outside any enclosing JPA transaction so the
     * status flip writes immediately. If credit fails after deduct
     * succeeded, the FAILED row carries the diagnostic; the initiator's
     * balance is debited but the counterparty wasn't credited.
     *
     * <p>Money-safety ordering — deduct FIRST, credit SECOND: a failed deduct
     * means no money moved (safe to retry), whereas a failed credit after a
     * successful deduct is the dangerous case and is flagged [RECONCILE] for
     * an operator. Only the principal moves; the computed fee/НДС are quoted,
     * not collected (no treasury rail yet), and that is logged explicitly.
     *
     * @param row the PENDING settlement row to execute
     * @return the final settlement state (COMPLETED, or FAILED with a tagged
     *         diagnostic) as a response DTO
     */
    private B2BSettlementResponse execute(B2BSettlement row) {
        try {
            tokenClient.deduct(row.getFromUserId(), row.getTokenId(), row.getAmount());
        } catch (InsufficientBalanceException ex) {
            return markFailed(row.getId(), "Deduct rejected: " + ex.getMessage(), true);
        } catch (RuntimeException ex) {
            log.error("B2B settlement {} — deduct call to token-service failed: {}",
                    row.getId(), ex.toString(), ex);
            return markFailed(row.getId(),
                    "Deduct call failed (no debit applied): " + ex.getMessage(), true);
        }

        try {
            tokenClient.credit(row.getToUserId(), row.getTokenId(), row.getAmount());
        } catch (RuntimeException ex) {
            log.error("B2B settlement {} — CRITICAL: deduct succeeded but credit failed. " +
                    "Initiator {} debited {} of token {}, counterparty {} NOT credited. " +
                    "Manual reconciliation required.",
                    row.getId(), row.getFromUserId(), row.getAmount(),
                    row.getTokenId(), row.getToUserId(), ex);
            return markFailed(row.getId(),
                    "Credit failed AFTER deduct succeeded — manual reconciliation required: "
                            + ex.getMessage(), false);
        }

        // NOTE (prototype limitation): only the principal (`amount`) is moved.
        // The grossFee/vat/net stored on the row are an INFORMATIONAL quote for
        // the 1С/ФНС export schema — there is no treasury settlement rail yet
        // (DLMM-TREASURY is only an export label), so NO fee is actually
        // collected. Log it explicitly so reporting/1С treats these columns as
        // quoted-not-collected rather than realised revenue / ФНС liability.
        if (row.getGrossFeeAmount() > 0) {
            log.info("B2B settlement {} — fee {}/НДС {} is COMPUTED but NOT settled "
                    + "(no treasury rail); principal {} moved only",
                    row.getId(), row.getGrossFeeAmount(), row.getVatAmount(), row.getAmount());
        }
        return markCompleted(row.getId());
    }

    /**
     * Flips the row to COMPLETED with a {@code completedAt} stamp in its own
     * short transaction (the deduct+credit already succeeded by this point).
     *
     * @param id id of the settlement to complete
     * @return the completed settlement as a response DTO
     * @throws TransactionFailedException if the row disappeared between phases
     */
    private B2BSettlementResponse markCompleted(UUID id) {
        B2BSettlement row = txTemplate.execute(status -> {
            B2BSettlement r = repository.findById(id)
                    .orElseThrow(() -> new TransactionFailedException("B2B settlement vanished: " + id));
            r.setStatus(B2BSettlementStatus.COMPLETED);
            r.setCompletedAt(LocalDateTime.now());
            return repository.save(r);
        });
        log.info("B2B settlement COMPLETED id={} reference={}", id, row.getReference());
        return B2BSettlementResponse.from(row);
    }

    /**
     * Flips the row to FAILED in its own short transaction, prefixing the
     * stored {@code error_message} with a [CLEAN] / [RECONCILE] tag so
     * operators can grep for settlements that need manual reconciliation.
     *
     * @param id           id of the settlement to fail
     * @param message      human-readable failure diagnostic
     * @param balanceClean true if no balance was moved (caller can safely retry
     *                     with a fresh reference); false if the deduct landed
     *                     and operator action is needed. Stored as a prefix tag
     *                     in error_message ([CLEAN] / [RECONCILE]) for grep-ability.
     * @return the failed settlement as a response DTO
     * @throws TransactionFailedException if the row disappeared between phases
     */
    private B2BSettlementResponse markFailed(UUID id, String message, boolean balanceClean) {
        B2BSettlement row = txTemplate.execute(status -> {
            B2BSettlement r = repository.findById(id)
                    .orElseThrow(() -> new TransactionFailedException("B2B settlement vanished: " + id));
            r.setStatus(B2BSettlementStatus.FAILED);
            r.setErrorMessage((balanceClean ? "[CLEAN] " : "[RECONCILE] ") + message);
            return repository.save(r);
        });
        log.warn("B2B settlement FAILED id={} reference={} balanceClean={} reason={}",
                id, row.getReference(), balanceClean, message);
        return B2BSettlementResponse.from(row);
    }

    /**
     * Fetches one settlement by id. Caller-side authorization (party-or-admin)
     * is enforced by the controller.
     *
     * @param id settlement id to fetch
     * @return the settlement as a response DTO
     * @throws TransactionFailedException if no settlement with {@code id} exists
     */
    @Transactional(readOnly = true)
    public B2BSettlementResponse get(UUID id) {
        return repository.findById(id)
                .map(B2BSettlementResponse::from)
                .orElseThrow(() -> new TransactionFailedException("B2B settlement not found: " + id));
    }

    /**
     * Lists settlements where {@code userId} is either party (initiator or
     * counterparty), createdAt-DESC, with optional status and date-window
     * filters.
     *
     * @param userId the user who must be a party to the returned settlements
     * @param status optional status filter, or {@code null}
     * @param from   optional inclusive lower bound on createdAt, or {@code null}
     * @param to     optional inclusive upper bound on createdAt, or {@code null}
     * @param page   zero-based page index
     * @param size   page size
     * @return a {@link PageResponse} of {@link B2BSettlementResponse} rows
     */
    @Transactional(readOnly = true)
    public PageResponse<B2BSettlementResponse> listForUser(UUID userId,
                                                            B2BSettlementStatus status,
                                                            LocalDateTime from,
                                                            LocalDateTime to,
                                                            int page,
                                                            int size) {
        PageRequest pr = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<B2BSettlement> p = repository.findForUser(userId, status, from, to, pr);
        return new PageResponse<>(
                p.getContent().stream().map(B2BSettlementResponse::from).toList(),
                p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}
