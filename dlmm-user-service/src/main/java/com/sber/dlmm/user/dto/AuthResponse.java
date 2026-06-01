package com.sber.dlmm.user.dto;

/**
 * Response body returned by the auth endpoints (login / register / refresh).
 *
 * <p>Bundles the freshly issued credential pair with the caller's profile so
 * the client can populate its auth store in a single round-trip. The tokens
 * are bearer secrets — clients must store them securely and never log them.
 *
 * @param accessToken  short-lived JWT (HS384) presented as a bearer token on
 *                     subsequent API calls; carries role/KYC/tier claims
 *                     (credential — keep secret)
 * @param refreshToken longer-lived token used to obtain a new access token
 *                     once it expires (credential — keep secret)
 * @param expiresIn    access-token lifetime in seconds from issuance
 * @param user         the authenticated user's public profile
 */
public record AuthResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        UserProfileResponse user
) {
}
