package com.sber.dlmm.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for a self-service profile update — a partial update where
 * every field is optional.
 *
 * <p>Each component may be {@code null} to leave that attribute unchanged; the
 * validation constraints only apply when a value is supplied (e.g. a provided
 * email must be well-formed). Immutable identity fields ({@code sberId},
 * password) are intentionally not editable here.
 *
 * @param email     new contact email, or {@code null} to keep the current one;
 *                  if present, must be a valid email address
 * @param phone     new phone, or {@code null} to keep the current one; if
 *                  present, must match {@code +7} followed by 10 digits
 * @param firstName new given name, or {@code null} to keep the current one; if
 *                  present, 2–50 characters
 * @param lastName  new family name, or {@code null} to keep the current one; if
 *                  present, 2–50 characters
 */
public record UpdateProfileRequest(
        @Email
        String email,

        @Pattern(regexp = "\\+7\\d{10}")
        String phone,

        @Size(min = 2, max = 50)
        String firstName,

        @Size(min = 2, max = 50)
        String lastName
) {
}
