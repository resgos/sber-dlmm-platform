package com.sber.dlmm.transaction.dto;

import com.sber.dlmm.common.enums.TransactionStatus;
import com.sber.dlmm.common.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Outbound API representation of a {@link com.sber.dlmm.transaction.entity.Transaction}
 * ledger row — a flat projection returned by the transaction-service read
 * endpoints (single fetch, user history, admin list).
 *
 * <p>Amount components ({@code amountIn}, {@code amountOut}, {@code feeAmount})
 * are raw ×10⁴ integers; UIs convert to human units at the API boundary.
 * {@code feeRate} is a ratio and is left unscaled.
 *
 * @param id            transaction id
 * @param txType        kind of money movement (SWAP, LP add/remove, fee claim, …)
 * @param status        lifecycle status (PENDING / CONFIRMED / FAILED)
 * @param userId        owner of the transaction
 * @param poolId        pool touched, or null for non-pool flows
 * @param tokenInId     token paid in / debited
 * @param amountIn      amount paid in, raw ×10⁴ scale, or null when not applicable
 * @param tokenOutId    token received / credited
 * @param amountOut     amount received, raw ×10⁴ scale, or null when not applicable
 * @param feeAmount     fee charged, raw ×10⁴ scale
 * @param feeRate       effective fee rate applied (a ratio, not scaled)
 * @param binsCrossed   number of price bins a swap traversed (0 for non-swap rows)
 * @param idempotencyKey client-supplied idempotency token, if any
 * @param metadata      free-form JSON context blob, if any
 * @param errorMessage  failure reason when {@code status} is FAILED
 * @param createdAt     insert timestamp
 * @param updatedAt     last-modified timestamp
 * @param confirmedAt   when the row reached CONFIRMED, or null
 * @param reviewedAt    when an admin marked the row reviewed, or null if not reviewed
 * @param reviewedBy    admin user id who marked it reviewed, or null
 */
public record TransactionResponse(
    UUID id,
    TransactionType txType,
    TransactionStatus status,
    UUID userId,
    UUID poolId,
    UUID tokenInId,
    Long amountIn,
    UUID tokenOutId,
    Long amountOut,
    long feeAmount,
    BigDecimal feeRate,
    int binsCrossed,
    String idempotencyKey,
    String metadata,
    String errorMessage,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime confirmedAt,
    // Sprint 9-DS-r4 (P2-12) — admin "Mark reviewed" state. Null
    // when the row hasn't been reviewed yet; populated when an
    // admin POSTs /transactions/{id}/review. Surfaces to admin-bff
    // so its suspicious-detection can skip reviewed rows.
    LocalDateTime reviewedAt,
    UUID reviewedBy
) {}
