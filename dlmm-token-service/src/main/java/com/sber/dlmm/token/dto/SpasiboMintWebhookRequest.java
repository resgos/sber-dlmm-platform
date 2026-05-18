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
 */
public record SpasiboMintWebhookRequest(
        @NotNull UUID userId,
        @NotNull @Positive Long points,
        @NotBlank @Size(max = 128) String reference
) {
}
