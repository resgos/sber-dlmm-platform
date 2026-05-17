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
     * Idempotent submit. Returns the final state after the deduct/credit
     * attempt — a clean run returns COMPLETED, a deduct-rejection returns
     * FAILED with [CLEAN] error_message, a credit-after-deduct failure
     * returns FAILED with [RECONCILE] error_message.
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
                    .build();
            return repository.save(row);
        });

        log.info("B2B settlement PENDING id={} reference={} from={} to={} token={} amount={}",
                pending.getId(), req.reference(), initiator, req.counterpartyUserId(),
                req.tokenId(), req.amount());

        return execute(pending);
    }

    /**
     * Deduct → credit, outside any enclosing JPA transaction so the
     * status flip writes immediately. If credit fails after deduct
     * succeeded, the FAILED row carries the diagnostic; the initiator's
     * balance is debited but the counterparty wasn't credited.
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

        return markCompleted(row.getId());
    }

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
     * @param balanceClean true if no balance was moved (caller can safely retry
     *                     with a fresh reference); false if the deduct landed
     *                     and operator action is needed. Stored as a prefix tag
     *                     in error_message ([CLEAN] / [RECONCILE]) for grep-ability.
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

    @Transactional(readOnly = true)
    public B2BSettlementResponse get(UUID id) {
        return repository.findById(id)
                .map(B2BSettlementResponse::from)
                .orElseThrow(() -> new TransactionFailedException("B2B settlement not found: " + id));
    }

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
