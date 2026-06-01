package com.sber.dlmm.transaction.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Sprint 4 #4.6 — POST body for {@code /api/v1/transactions/b2b/settlements}.
 *
 * <p>The initiator (fromUser) is taken from the JWT, never the body — corp
 * operators cannot debit other corps' accounts via this prototype. {@code
 * counterpartyUserId} is the credited side. {@code reference} is the
 * idempotency anchor and must be unique platform-wide; recommended format
 * is {@code <ERP-system>-<doc-no>-<line>} for traceability.
 *
 * @param counterpartyUserId corp account to credit (the from-side comes from the JWT)
 * @param tokenId            token to transfer (same on both legs)
 * @param amount             principal to transfer, raw ×10⁴ scale; must be positive
 * @param reference          unique business reference; the idempotency anchor (max 128 chars)
 * @param notes              optional free-text note (max 500 chars)
 */
public record B2BSettlementRequest(
        @NotNull UUID counterpartyUserId,
        @NotNull UUID tokenId,
        @NotNull @Positive Long amount,
        @NotBlank @Size(max = 128) String reference,
        @Size(max = 500) String notes
) {
}
