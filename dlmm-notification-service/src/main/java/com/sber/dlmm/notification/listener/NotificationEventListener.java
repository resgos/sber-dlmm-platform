package com.sber.dlmm.notification.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sber.dlmm.common.enums.NotificationType;
import com.sber.dlmm.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationEventListener {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "pool-events", groupId = "dlmm-notification-service")
    public void handlePoolEvents(ConsumerRecord<String, String> record) {
        try {
            JsonNode node = objectMapper.readTree(record.value());
            String eventType = determinePoolEventType(node);

            switch (eventType) {
                case "SwapExecutedEvent" -> handleSwapExecuted(node);
                case "LiquidityAddedEvent" -> handleLiquidityAdded(node);
                default -> log.debug("Ignoring pool event type: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process pool event: {}", record.value(), e);
        }
    }

    @KafkaListener(topics = "fee-events", groupId = "dlmm-notification-service")
    public void handleFeeEvents(ConsumerRecord<String, String> record) {
        try {
            JsonNode node = objectMapper.readTree(record.value());

            if (node.has("positionId") && node.has("claimedX")) {
                UUID userId = UUID.fromString(node.get("userId").asText());
                long claimedX = node.get("claimedX").asLong();
                long claimedY = node.get("claimedY").asLong();

                notificationService.createNotification(
                        userId,
                        NotificationType.FEE_ACCRUED,
                        "Комиссии забраны",
                        String.format("Комиссии забраны: %d tokenX + %d tokenY", claimedX, claimedY),
                        record.value()
                );
                log.info("Created fee claimed notification for user {}", userId);
            }
        } catch (Exception e) {
            log.error("Failed to process fee event: {}", record.value(), e);
        }
    }

    @KafkaListener(topics = "user-events", groupId = "dlmm-notification-service")
    public void handleUserEvents(ConsumerRecord<String, String> record) {
        try {
            JsonNode node = objectMapper.readTree(record.value());

            if (node.has("userId") && node.has("email")) {
                UUID userId = UUID.fromString(node.get("userId").asText());
                String fullName = node.has("fullName") ? node.get("fullName").asText() : "User";

                notificationService.createNotification(
                        userId,
                        NotificationType.KYC_APPROVED,
                        "KYC верификация пройдена",
                        String.format("Уважаемый %s, ваша KYC верификация пройдена. Вам предоставлен полный доступ к платформе.", fullName),
                        record.value()
                );
                log.info("Created KYC verified notification for user {}", userId);
            }
        } catch (Exception e) {
            log.error("Failed to process user event: {}", record.value(), e);
        }
    }

    private String determinePoolEventType(JsonNode node) {
        if (node.has("tokenInId") && node.has("amountIn") && node.has("fee")) {
            return "SwapExecutedEvent";
        }
        if (node.has("positionId") && node.has("amountX") && node.has("amountY") && !node.has("fee")) {
            return "LiquidityAddedEvent";
        }
        return "Unknown";
    }

    private void handleSwapExecuted(JsonNode node) {
        UUID userId = UUID.fromString(node.get("userId").asText());
        long amountIn = node.get("amountIn").asLong();
        long amountOut = node.get("amountOut").asLong();

        notificationService.createNotification(
                userId,
                NotificationType.SWAP_COMPLETED,
                "Своп выполнен",
                String.format("Своп выполнен: %d вход → %d выход", amountIn, amountOut),
                node.toString()
        );
        log.info("Created swap notification for user {}", userId);
    }

    private void handleLiquidityAdded(JsonNode node) {
        UUID userId = UUID.fromString(node.get("userId").asText());
        UUID poolId = UUID.fromString(node.get("poolId").asText());
        long amountX = node.get("amountX").asLong();
        long amountY = node.get("amountY").asLong();

        notificationService.createNotification(
                userId,
                NotificationType.SYSTEM_ALERT,
                "Ликвидность добавлена",
                String.format("Ликвидность добавлена в пул %s: %d tokenX + %d tokenY", poolId, amountX, amountY),
                node.toString()
        );
        log.info("Created liquidity added notification for user {}", userId);
    }
}
