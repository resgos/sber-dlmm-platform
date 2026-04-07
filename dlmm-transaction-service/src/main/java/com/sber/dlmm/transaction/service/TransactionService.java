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
                .metadata(metadata)
                .build();

        Transaction saved = transactionRepository.save(transaction);
        log.info("Created transaction id={} type={} userId={}", saved.getId(), type, userId);
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
        Page<Transaction> transactionPage = transactionRepository.findFiltered(userId, type, status, from, to, pageRequest);

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
                tx.getConfirmedAt()
        );
    }
}
