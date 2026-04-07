package com.sber.dlmm.user.dto;

import com.sber.dlmm.common.enums.UserRole;
import jakarta.validation.constraints.NotNull;

public record UpdateRoleRequest(
        @NotNull
        UserRole role
) {
}
