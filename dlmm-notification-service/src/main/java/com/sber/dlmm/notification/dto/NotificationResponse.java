package com.sber.dlmm.notification.dto;

import com.sber.dlmm.common.enums.NotificationType;

import java.time.LocalDateTime;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        UUID userId,
        NotificationType type,
        String title,
        String message,
        String payload,
        boolean read,
        LocalDateTime createdAt,
        LocalDateTime readAt
) {}
