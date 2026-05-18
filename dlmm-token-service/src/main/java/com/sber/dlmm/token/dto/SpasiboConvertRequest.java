package com.sber.dlmm.token.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Sprint 5 #5.4 — user-initiated SSPAS → SRUB conversion request.
 * Caller is the JWT subject (no userId in body — user can only convert
 * their own points). {@code reference} is client-generated for retry safety
 * (UUID recommended).
 */
public record SpasiboConvertRequest(
        @NotNull @Positive Long points,
        @NotBlank @Size(max = 128) String reference
) {
}
