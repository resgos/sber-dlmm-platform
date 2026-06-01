package com.sber.dlmm.token.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Sprint 5 #5.3 — body for the SberSpasibo BU → DLMM webhook.
 * Posted by Spasibo loyalty system when a user earns points from a
 * real-world transaction (cashback, partner promo, etc).
 *
 * <p>{@code reference} is the Spasibo-system event id and serves as
 * idempotency anchor — Spasibo BU guarantees at-least-once delivery,
 * we guarantee at-most-once application via this reference.
 *
 * @param userId    DLMM user to credit the earned points to (required)
 * @param points    number of SSPAS loyalty points earned — a whole-point count, not a ×10⁴ scaled amount (required, &gt; 0)
 * @param reference Spasibo-side event id used as the idempotency anchor (required, non-blank, ≤ 128 chars)
 */
public record SpasiboMintWebhookRequest(
        @NotNull UUID userId,
        @NotNull @Positive Long points,
        @NotBlank @Size(max = 128) String reference
) {
}
