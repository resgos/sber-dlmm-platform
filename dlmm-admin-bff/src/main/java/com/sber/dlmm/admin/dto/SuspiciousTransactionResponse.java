package com.sber.dlmm.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One flagged row in the admin AML review queue, served by
 * {@code GET /api/v1/admin/transactions/suspicious}.
 *
 * <p>Produced by {@code AdminService} when a recent transaction trips an AML
 * heuristic (e.g. a swap exceeding 5% of pool TVL, a user with more than 50
 * transactions in the scanned batch, or price impact above 3%). Once an admin
 * marks the underlying transaction reviewed, it drops out of this feed.
 *
 * @param transactionId  identifier of the flagged transaction
 * @param userId         identifier of the user who initiated the transaction
 * @param poolId         identifier of the pool the transaction touched
 * @param txType         operation type of the flagged transaction (SWAP / TRANSFER / …);
 *                       2026-06-17 — was absent, leaving the admin table's «Тип»
 *                       column permanently blank even though the scan already
 *                       reads the type for its TVL heuristic
 * @param reason         human-readable AML heuristic that flagged this row
 * @param amount         transaction amount, raw integer (1 unit = 10⁻⁴ token)
 * @param priceImpactPct price impact of the transaction, as a percentage (%)
 * @param timestamp      time the transaction occurred
 */
public record SuspiciousTransactionResponse(
    UUID transactionId,
    UUID userId,
    UUID poolId,
    String txType,
    String reason,
    long amount,
    BigDecimal priceImpactPct,
    LocalDateTime timestamp
) {}
