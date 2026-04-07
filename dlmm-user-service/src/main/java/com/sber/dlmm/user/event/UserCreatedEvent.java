package com.sber.dlmm.user.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserCreatedEvent(
        UUID userId,
        String sberId,
        String email,
        LocalDateTime createdAt
) {
}
