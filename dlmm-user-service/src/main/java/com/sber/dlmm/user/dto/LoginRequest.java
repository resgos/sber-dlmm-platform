package com.sber.dlmm.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/auth/login} — email + password
 * credentials.
 *
 * <p>Bean-validation rejects a malformed payload before it reaches the service
 * (required email of valid form; required non-blank password).
 *
 * @param email    the account email; required and must be a valid email
 *                 address
 * @param password the plaintext password to verify against the stored BCrypt
 *                 hash; required and non-blank (credential — never logged)
 */
public record LoginRequest(
        @NotBlank @Email
        String email,

        @NotBlank
        String password
) {
}
