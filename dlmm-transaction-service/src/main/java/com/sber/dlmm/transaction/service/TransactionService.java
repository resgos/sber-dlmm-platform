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
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Core ledger service for the transaction-service — the single write/read
 * gateway over the {@code transactions} table.
 *
 * <p>Role: owns the lifecycle of every ledger row (swap, LP add/remove, fee
 * claim, OTC settlement, B2B settlement, transfer). Rows are created in
 * {@link TransactionStatus#CREATED} and walk CREATED → CONFIRMED (happy path)
 * or CREATED → FAILED. The pool-engine swap bridge ({@code SwapEventConsumer})
 * is the highest-volume writer; everything else reads.
 *
 * <p>Invariants / guarantees:
 * <ul>
 *   <li>Dedup keys — a row may carry an {@code idempotencyKey} (client/flow
 *       supplied) and/or a {@code poolEngineTxId} (originating swap row UUID).
 *       Both columns are UNIQUE in the schema, so concurrent consumers cannot
 *       double-insert the same logical event; {@link #findByIdempotencyKey}
 *       and {@link #findByPoolEngineTxId} are the application-level fast-path
 *       checks the consumer uses before INSERT.</li>
 *   <li>Read methods are {@code @Transactional(readOnly = true)} and map the
 *       JPA entity to the API-facing {@link TransactionResponse} via
 *       {@link #toResponse} so the entity never leaks past this layer.</li>
 *   <li>Mutators look the row up by id and throw
 *       {@link TransactionFailedException} when it is absent, rather than
 *       returning null.</li>
 * </ul>
 *
 * <p>Lombok {@code @RequiredArgsConstructor} generates the constructor that
 * injects {@link TransactionRepository}; {@code @Slf4j} supplies {@code log}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    private final TransactionRepository transactionRepository;
    /** Audit B3 — resolves token symbols + pool pair label for the read DTO. */
    private final TxLabelResolver txLabelResolver;

    /**
     * Creates a ledger row without a pool-engine origin reference — the
     * historical signature used by every non-swap transaction type (LP
     * add/remove, fee claim, OTC, B2B settlement). Delegates to the
     * {@code poolEngineTxId}-aware overload passing {@code null} so there is a
     * single creation path.
     *
     * @param type           transaction kind (SWAP, ADD_LIQUIDITY, …)
     * @param userId         owning user
     * @param poolId         pool the operation touched, or {@code null} for
     *                       pool-less flows (e.g. plain transfers)
     * @param tokenInId      token debited from the user, or {@code null}
     * @param amountIn       raw amount in (10⁻⁴ token units), or {@code null}
     * @param tokenOutId     token credited to the user, or {@code null}
     * @param amountOut      raw amount out (10⁻⁴ token units), or {@code null}
     * @param fee            fee charged, in raw units
     * @param feeRate        effective fee rate (fee ÷ amountIn) for the row
     * @param binsCrossed    number of DLMM bins the swap traversed (0 for
     *                       non-swap rows)
     * @param idempotencyKey client/flow dedup key, or {@code null}
     * @param metadata       free-form JSON metadata, or {@code null}
     * @return the persisted transaction in {@link TransactionStatus#CREATED}
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
     *
     * <p>This is the single physical creation path: builds the entity in
     * {@link TransactionStatus#CREATED}, saves it, and logs the new id. The
     * {@code poolEngineTxId} is what makes swap persistence idempotent across
     * Kafka redeliveries (UNIQUE column), so it is preserved verbatim here.
     *
     * @param type           transaction kind (SWAP, ADD_LIQUIDITY, …)
     * @param userId         owning user
     * @param poolId         pool the operation touched, or {@code null}
     * @param tokenInId      token debited from the user, or {@code null}
     * @param amountIn       raw amount in (10⁻⁴ token units), or {@code null}
     * @param tokenOutId     token credited to the user, or {@code null}
     * @param amountOut      raw amount out (10⁻⁴ token units), or {@code null}
     * @param fee            fee charged, in raw units
     * @param feeRate        effective fee rate (fee ÷ amountIn) for the row
     * @param binsCrossed    number of DLMM bins the swap traversed
     * @param idempotencyKey client/flow dedup key, or {@code null}
     * @param poolEngineTxId originating pool-engine swap row UUID (the swap
     *                       dedup key), or {@code null} for non-swap rows
     * @param metadata       free-form JSON metadata, or {@code null}
     * @return the persisted transaction in {@link TransactionStatus#CREATED}
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

    /**
     * Generic status setter used when the caller already knows the target
     * state (and optionally an error message). Unlike {@link #confirm} /
     * {@link #fail} it does not stamp {@code confirmedAt}, so prefer those
     * for the canonical terminal transitions; this is the escape hatch for
     * intermediate or externally-driven states.
     *
     * @param txId         id of the transaction to update
     * @param status       new status to set
     * @param errorMessage diagnostic to attach, or {@code null} to leave the
     *                     existing message untouched
     * @return the updated, persisted transaction
     * @throws TransactionFailedException if no transaction with {@code txId} exists
     */
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
     *
     * <p>Why: admin-bff derives the "suspicious" list on the fly, so stamping
     * these columns is what tells it to stop re-surfacing a row an operator
     * has already cleared.
     *
     * @param txId           id of the transaction being reviewed
     * @param reviewerUserId id of the admin performing the review
     * @return the updated, persisted transaction
     * @throws TransactionFailedException if no transaction with {@code txId} exists
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

    /**
     * Canonical success transition: CREATED → {@link TransactionStatus#CONFIRMED}
     * with a {@code confirmedAt} timestamp. Used by the swap consumer right
     * after persisting a swap (pool-engine already moved the funds atomically,
     * so the row is confirmed immediately).
     *
     * @param txId id of the transaction to confirm
     * @return the confirmed, persisted transaction
     * @throws TransactionFailedException if no transaction with {@code txId} exists
     */
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

    /**
     * Canonical failure transition: → {@link TransactionStatus#FAILED} with a
     * diagnostic message persisted for later inspection.
     *
     * @param txId         id of the transaction to fail
     * @param errorMessage human-readable failure reason stored on the row
     * @return the failed, persisted transaction
     * @throws TransactionFailedException if no transaction with {@code txId} exists
     */
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

    /**
     * Fetches a single transaction as an API response DTO. Authorization
     * (owner-or-admin) is enforced by the controller, not here.
     *
     * @param txId id of the transaction to fetch
     * @return the transaction mapped to {@link TransactionResponse}
     * @throws TransactionFailedException if no transaction with {@code txId} exists
     */
    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(UUID txId) {
        Transaction transaction = transactionRepository.findById(txId)
                .orElseThrow(() -> new TransactionFailedException("Transaction not found"));
        return toResponse(transaction);
    }

    /**
     * Paginated, createdAt-DESC history for one user, with optional type and
     * status filters. Picks the narrowest derived-query method matching which
     * of {@code type}/{@code status} are supplied (avoids JPQL with nullable
     * enum params, which Hibernate handles poorly).
     *
     * @param userId user whose transactions to list
     * @param type   optional transaction-type filter, or {@code null}
     * @param status optional status filter, or {@code null}
     * @param from   currently unused window lower bound (reserved; the chosen
     *               repository methods do not filter by date)
     * @param to     currently unused window upper bound (reserved)
     * @param page   zero-based page index
     * @param size   page size
     * @return a {@link PageResponse} of {@link TransactionResponse} rows
     */
    @Transactional(readOnly = true)
    public PageResponse<TransactionResponse> getUserTransactions(UUID userId,
                                                                   TransactionType type,
                                                                   TransactionStatus status,
                                                                   LocalDateTime from,
                                                                   LocalDateTime to,
                                                                   int page,
                                                                   int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        // Dynamic filter: user + ANY combination of type / status / createdAt window.
        // Replaces the if-else ladder of named queries, which had no date branch at
        // all — so the `from`/`to` window was silently dropped and the UI's date
        // filter never worked. A Specification composes the optional predicates
        // without a combinatorial explosion of repository methods.
        Specification<Transaction> spec = (root, query, cb) -> cb.equal(root.get("userId"), userId);
        if (type != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("txType"), type));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (from != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.<LocalDateTime>get("createdAt"), from));
        }
        if (to != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.<LocalDateTime>get("createdAt"), to));
        }
        Page<Transaction> transactionPage = transactionRepository.findAll(spec, pageRequest);

        return new PageResponse<>(
                transactionPage.getContent().stream().map(this::toResponse).toList(),
                transactionPage.getNumber(),
                transactionPage.getSize(),
                transactionPage.getTotalElements(),
                transactionPage.getTotalPages()
        );
    }

    /**
     * Platform-wide admin feed: every user's transactions, createdAt-DESC,
     * with optional type/status filters applied through a single repository
     * query. Backs the admin transactions table.
     *
     * @param type   optional transaction-type filter, or {@code null}
     * @param status optional status filter, or {@code null}
     * @param page   zero-based page index
     * @param size   page size
     * @return a {@link PageResponse} of {@link TransactionResponse} rows
     */
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

    /**
     * Primary dedup lookup: finds an existing row by its client/flow-supplied
     * idempotency key. The swap consumer calls this before INSERT so a
     * re-delivered Kafka message does not create a duplicate ledger row.
     *
     * @param key the idempotency key to look up
     * @return the matching transaction, or {@link Optional#empty()} if none
     */
    @Transactional(readOnly = true)
    public Optional<Transaction> findByIdempotencyKey(String key) {
        return transactionRepository.findByIdempotencyKey(key);
    }

    /**
     * Sprint 9-DS-r4 (P0-4) — secondary dedup lookup for the swap
     * event consumer (see {@code SwapEventConsumer}).
     *
     * <p>Used in addition to {@link #findByIdempotencyKey} because swaps that
     * carry no client idempotency key still always carry the pool-engine swap
     * row UUID, giving the consumer a second dedup key.
     *
     * @param poolEngineTxId the originating pool-engine swap row UUID
     * @return the matching transaction, or {@link Optional#empty()} if none
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
     *
     * @param poolId pool whose recent swaps to return
     * @param limit  requested row count; clamped to the range [1, 100]
     * @return newest-first list of recent SWAP rows as {@link TransactionResponse}
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

    /**
     * Loads the entity rows backing a settlement report (CSV or 1C export).
     * Returns raw {@link Transaction} entities — not response DTOs — because
     * the export formatters need fields the API DTO doesn't surface. Hard-capped
     * at {@link #REPORT_MAX_ROWS} rows; callers needing more must narrow the
     * date window.
     *
     * @param userId user whose transactions to report on
     * @param from   optional inclusive lower bound on createdAt, or {@code null}
     * @param to     optional inclusive upper bound on createdAt, or {@code null}
     * @return up to {@link #REPORT_MAX_ROWS} matching transactions, createdAt-DESC
     */
    @Transactional(readOnly = true)
    public java.util.List<Transaction> findForReport(UUID userId,
                                                      LocalDateTime from,
                                                      LocalDateTime to) {
        PageRequest pageRequest = PageRequest.of(0, REPORT_MAX_ROWS,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return transactionRepository.findFiltered(userId, null, null, from, to, pageRequest)
                .getContent();
    }

    /**
     * Maps a persisted {@link Transaction} entity to the immutable
     * {@link TransactionResponse} API DTO, copying every exposed field
     * (including the Sprint 9 mark-reviewed columns). Keeps the JPA entity
     * from leaking past the service boundary.
     *
     * @param tx the entity to map
     * @return the corresponding response DTO
     */
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
                tx.getReviewedBy(),
                // Audit B3 — resolved, self-describing labels (best-effort, cached).
                txLabelResolver.tokenSymbol(tx.getTokenInId()),
                txLabelResolver.tokenSymbol(tx.getTokenOutId()),
                txLabelResolver.poolPair(tx.getPoolId())
        );
    }
}
