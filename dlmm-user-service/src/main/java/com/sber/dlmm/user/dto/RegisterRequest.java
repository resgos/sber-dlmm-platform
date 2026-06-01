package com.sber.dlmm.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/register} — new-account
 * sign-up details.
 *
 * <p>Every field is bean-validated so a malformed payload is rejected before
 * the service runs. On success the service hashes the password, persists a
 * {@link com.sber.dlmm.user.entity.User} (defaulting KYC to {@code PENDING}
 * and role to {@code USER}), and returns an {@link AuthResponse}.
 *
 * @param sberId    the Sber ecosystem identifier; required and non-blank,
 *                  must be unique (rejected if already taken)
 * @param email     contact + login email; required and a valid email address
 * @param phone     contact phone; required and must match {@code +7} followed
 *                  by exactly 10 digits
 * @param firstName given name; required, 2–50 characters
 * @param lastName  family name; required, 2–50 characters
 * @param password  plaintext password; required, at least 8 characters and
 *                  must contain at least one letter and one digit. Hashed
 *                  (BCrypt) before storage — never persisted or logged in
 *                  plaintext (credential)
 */
public record RegisterRequest(
        @NotBlank
        String sberId,

        @NotBlank @Email
        String email,

        @NotBlank @Pattern(regexp = "\\+7\\d{10}")
        String phone,

        @NotBlank @Size(min = 2, max = 50)
        String firstName,

        @NotBlank @Size(min = 2, max = 50)
        String lastName,

        @NotBlank @Size(min = 8) @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$")
        String password
) {
}
