package com.sber.dlmm.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/auth/refresh} — exchanges a valid
 * refresh token for a new access token (and {@link AuthResponse}).
 *
 * @param refreshToken the refresh token previously issued at login/register;
 *                     required and non-blank (credential — never logged)
 */
public record RefreshTokenRequest(
        @NotBlank
        String refreshToken
) {
}
