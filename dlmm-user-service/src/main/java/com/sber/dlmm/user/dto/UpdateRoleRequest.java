package com.sber.dlmm.user.dto;

import com.sber.dlmm.common.enums.UserRole;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for an admin endpoint that changes a user's authorization
 * role.
 *
 * @param role the new role to assign to the target user; required (must be a
 *             valid {@link UserRole} enum value)
 */
public record UpdateRoleRequest(
        @NotNull
        UserRole role
) {
}
