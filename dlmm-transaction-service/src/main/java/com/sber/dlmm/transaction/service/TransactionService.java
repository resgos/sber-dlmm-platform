package com.sber.dlmm.transaction.service;

import com.sber.dlmm.common.dto.PageResponse;
import com.sber.dlmm.common.enums.TransactionStatus;
import com.sber.dlmm.common.enums.TransactionType;
import com.sber.dlmm.common.exception.TransactionFailedException;
import com.sber.dlmm.transaction.dto.TransactionResponse;
import com.sber.dlmm.transaction.entity.Transaction;
import com.sber.dlmm.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    private final TransactionRepository transactionRepository;

    @Transactional
    public Transaction createTransaction(TransactionType type,
                                          UUID userId,
                                          UUID poolId,
                                          UUID tokenInId,
                                          Long amountIn,
                                          UUID tokenOutId,
                                          Long amountOut,
                                          long fee,
                                          BigDecimal feeRate,
                                          int binsCrossed,
                                          String idempotencyKey,
                                          String metadata) {
        return createTransaction(type, userId, poolId, tokenInId, amountIn,
                tokenOutId, amountOut, fee, feeRate, binsCrossed,
                idempotencyKey, /*poolEngineTxId*/ null, metadata);
    }

    /**
     * Sprint 9-DS-r4 (P0-4) — overload that stamps the originating
     * pool-engine swap row UUID. {@code SwapEventConsumer} is the only
     * caller in practice; other transaction types (LP add/remove, fee
     * claim, OTC, B2B settlement) keep using the old signature and
     * leave {@code poolEngineTxId} null.
     */
    @Transactional
    public Transaction createTransaction(TransactionType type,
                                          UUID userId,
                                          UUID poolId,
                                          UUID tokenInId,
                                          Long amountIn,
                                          UUID tokenOutId,
                                          Long amountOut,
                                          long fee,
                                          BigDecimal feeRate,
                                          int binsCrossed,
                                          String idempotencyKey,
                                          UUID poolEngineTxId,
                                          String metadata) {
        Transaction transaction = Transaction.builder()
                .txType(type)
                .status(TransactionStatus.CREATED)
                .userId(userId)
                .poolId(poolId)
                .tokenInId(tokenInId)
                .amountIn(amountIn)
                .tokenOutId(tokenOutId)
                .amountOut(amountOut)
                .feeAmount(fee)
                .feeRate(feeRate)
                .binsCrossed(binsCrossed)
                .idempotencyKey(idempotencyKey)
                .poolEngineTxId(poolEngineTxId)
                .metadata(metadata)
                .build();

        Transaction saved = transactionRepository.save(transaction);
        log.info("Created transaction id={} type={} userId={} poolEngineTxId={}",
                saved.getId(), type, userId, poolEngineTxId);
        return saved;
    }

    @Transactional
    public Transaction updateStatus(UUID txId, TransactionStatus status, String errorMessage) {
        Transaction transaction = transactionRepository.findById(txId)
                .orElseThrow(() -> new TransactionFailedException("Transaction not found"));

        transaction.setStatus(status);
        if (errorMessage != null) {
            transaction.setErrorMessage(errorMessage);
        }

        Transaction saved = transactionRepository.save(transaction);
        log.info("Updated transaction id={} status={}", txId, status);
        return saved;
    }

    /**
     * Sprint 9-DS-r4 (P2-12) — admin "Mark reviewed" action for the
     * SuspiciousTransactionsPage. Stamps reviewedAt/reviewedBy on the
     * transaction; idempotent (re-reviewing a reviewed row updates
     * the reviewedBy/At to the most recent reviewer).
     */
    @Transactional
    public Transaction markReviewed(UUID txId, UUID reviewerUserId) {
        Transaction transaction = transactionRepository.findById(txId)
                .orElseThrow(() -> new TransactionFailedException("Transaction not found"));
        transaction.setReviewedAt(LocalDateTime.now());
        transaction.setReviewedBy(reviewerUserId);
        Transaction saved = transactionRepository.save(transaction);
        log.info("Transaction marked reviewed: id={} by={}", txId, reviewerUserId);
        return saved;
    }

    @Transactional
    public Transaction confirm(UUID txId) {
        Transaction transaction = transactionRepository.findById(txId)
                .orElseThrow(() -> new TransactionFailedException("Transaction not found"));

        transaction.setStatus(TransactionStatus.CONFIRMED);
        transaction.setConfirmedAt(LocalDateTime.now());

        Transaction saved = transactionRepository.save(transaction);
        log.info("Confirmed transaction id={}", txId);
        return saved;
    }

    @Transactional
    public Transaction fail(UUID txId, String errorMessage) {
        Transaction transaction = transactionRepository.findById(txId)
                .orElseThrow(() -> new TransactionFailedException("Transaction not found"));

        transaction.setStatus(TransactionStatus.FAILED);
        transaction.setErrorMessage(errorMessage);

        Transaction saved = transactionRepository.save(transaction);
        log.info("Failed transaction id={} error={}", txId, errorMessage);
        return saved;
    }

    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(UUID txId) {
        Transaction transaction = transactionRepository.findById(txId)
                .orElseThrow(() -> new TransactionFailedException("Transaction not found"));
        return toResponse(transaction);
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> getUserTransactions(UUID userId,
                                                                   TransactionType type,
                                                                   TransactionStatus status,
                                                                   LocalDateTime from,
                                                                   LocalDateTime to,
                                                                   int page,
                                                                   int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Transaction> transactionPage;
        if (type != null && status != null) {
            transactionPage = transactionRepository.findByUserIdAndTxTypeAndStatus(userId, type, status, pageRequest);
        } else if (type != null) {
            transactionPage = transactionRepository.findByUserIdAndTxType(userId, type, pageRequest);
        } else if (status != null) {
            transactionPage = transactionRepository.findByUserIdAndStatus(userId, status, pageRequest);
        } else {
            transactionPage = transactionRepository.findByUserId(userId, pageRequest);
        }

        return new PageResponse<>(
                transactionPage.getContent().stream().map(this::toResponse).toList(),
                transactionPage.getNumber(),
                transactionPage.getSize(),
                transactionPage.getTotalElements(),
                transactionPage.getTotalPages()
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> getAllTransactions(TransactionType type,
                                                                TransactionStatus status,
                                                                int page,
                                                                int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Transaction> transactionPage = transactionRepository.findAllFiltered(type, status, pageRequest);
        return new PageResponse<>(
                transactionPage.getContent().stream().map(this::toResponse).toList(),
                transactionPage.getNumber(),
                transactionPage.getSize(),
                transactionPage.getTotalElements(),
                transactionPage.getTotalPages()
        );
    }

    @Transactional(readOnly = true)
    public Optional<Transaction> findByIdempotencyKey(String key) {
        return transactionRepository.findByIdempotencyKey(key);
    }

    /**
     * Sprint 9-DS-r4 (P0-4) — secondary dedup lookup for the swap
     * event consumer (see {@code SwapEventConsumer}).
     */
    @Transactional(readOnly = true)
    public Optional<Transaction> findByPoolEngineTxId(UUID poolEngineTxId) {
        return transactionRepository.findByPoolEngineTxId(poolEngineTxId);
    }

    /**
     * Sprint 9-DS-r4 (P1-6) — pool-scoped recent SWAP feed for the
     * Meteora-style "История" panel on user-ui PoolDetailPage. Caller
     * limit is clamped to [1, 100] so a stray {@code ?limit=10000}
     * can't OOM the bff.
     */
    @Transactional(readOnly = true)
    public java.util.List<TransactionResponse> getRecentPoolTransactions(UUID poolId, int limit) {
        int clamped = Math.max(1, Math.min(limit, 100));
        PageRequest pr = PageRequest.of(0, clamped);
        return transactionRepository.findRecentPoolSwaps(poolId, pr)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Sprint 4 #4.4 — settlement-report CSV row source.
     * Returns up to {@link #REPORT_MAX_ROWS} transactions matching the
     * filter, sorted by createdAt DESC. Capped to bound memory; corp
     * accountants who need bigger windows should paginate by date range.
     */
    private static final int REPORT_MAX_ROWS = 10_000;

    @Transactional(readOnly = true)
    public java.util.List<Transaction> findForReport(UUID userId,
                                                      LocalDateTime from,
                                                      LocalDateTime to) {
        PageRequest pageRequest = PageRequest.of(0, REPORT_MAX_ROWS,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return transactionRepository.findFiltered(userId, null, null, from, to, pageRequest)
                .getContent();
    }

    private TransactionResponse toResponse(Transaction tx) {
        return new TransactionResponse(
                tx.getId(),
                tx.getTxType(),
                tx.getStatus(),
                tx.getUserId(),
                tx.getPoolId(),
                tx.getTokenInId(),
                tx.getAmountIn(),
                tx.getTokenOutId(),
                tx.getAmountOut(),
                tx.getFeeAmount(),
                tx.getFeeRate(),
                tx.getBinsCrossed(),
                tx.getIdempotencyKey(),
                tx.getMetadata(),
                tx.getErrorMessage(),
                tx.getCreatedAt(),
                tx.getUpdatedAt(),
                tx.getConfirmedAt(),
                // Sprint 9-DS-r4 (P2-12) — Mark-reviewed state.
                tx.getReviewedAt(),
                tx.getReviewedBy()
        );
    }
}
