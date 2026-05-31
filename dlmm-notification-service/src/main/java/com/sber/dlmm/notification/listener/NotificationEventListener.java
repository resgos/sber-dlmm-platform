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
                case "LimitOrderFilledEvent" -> handleLimitOrderFilled(node);
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

            // Sprint 5 #5.15 — margin-call events from pool-engine
            // MarginWatchService (#4.3). Routed via user-events topic
            // (single notification entry-point per user). Distinguishing
            // shape: has `eventType` AND `rangeMin`/`rangeMax`.
            if (node.has("eventType") && node.has("rangeMin") && node.has("rangeMax")) {
                handleMarginEvent(node, record.value());
                return;
            }

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

    /**
     * Sprint 5 #5.15 — render margin-call / margin-warning event into
     * the user's notification panel. notification-service is the only
     * service writing to {@code notifications} table; pool-engine
     * publishes the raw event and we craft the user-facing message here.
     */
    private void handleMarginEvent(JsonNode node, String rawPayload) {
        String eventTypeStr = node.get("eventType").asText();
        NotificationType type = NotificationType.valueOf(eventTypeStr);
        UUID userId = UUID.fromString(node.get("userId").asText());
        UUID positionId = UUID.fromString(node.get("positionId").asText());
        int activeBin = node.get("activeBinId").asInt();
        int rangeMin = node.get("rangeMin").asInt();
        int rangeMax = node.get("rangeMax").asInt();
        int distance = node.get("distanceFromBoundary").asInt();
        String deadline = node.has("rebalanceDeadline") && !node.get("rebalanceDeadline").isNull()
                ? node.get("rebalanceDeadline").asText() : null;

        String title;
        String message;
        if (type == NotificationType.MARGIN_CALL) {
            title = "Маржин-колл по LP-позиции";
            message = String.format(
                    "Позиция %s вышла из диапазона: активный бин %d, диапазон [%d, %d], отклонение %d бинов. " +
                    "Комиссии не начисляются.%s",
                    positionId, activeBin, rangeMin, rangeMax, Math.abs(distance),
                    deadline != null ? " Перебалансировать к " + deadline + "." : "");
        } else { // MARGIN_WARNING
            title = "Предупреждение по LP-позиции";
            message = String.format(
                    "Позиция %s приближается к границе диапазона: активный бин %d, " +
                    "до границы %d бинов. Рассмотрите ребалансировку.%s",
                    positionId, activeBin, distance,
                    deadline != null ? " Срок принятия решения: " + deadline + "." : "");
        }

        notificationService.createNotification(userId, type, title, message, rawPayload);
        log.info("Created {} notification for user={} position={}", type, userId, positionId);
    }

    private String determinePoolEventType(JsonNode node) {
        // Sprint 16 — limit-order fill (unique fields limitOrderId + fillPrice;
        // the Placed/Cancelled siblings lack fillPrice so they fall through to
        // the default-ignore, which is intended — only fills get a notification).
        if (node.has("limitOrderId") && node.has("fillPrice")) {
            return "LimitOrderFilledEvent";
        }
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

    /**
     * Sprint 16 (Meteora parity) — a limit order filled (possibly long after it
     * was placed), so the user must be told. Reuses SWAP_COMPLETED (a fill is an
     * executed trade) to avoid a cross-service NotificationType enum change.
     */
    private void handleLimitOrderFilled(JsonNode node) {
        UUID userId = UUID.fromString(node.get("userId").asText());
        notificationService.createNotification(
                userId,
                NotificationType.SWAP_COMPLETED,
                "Лимитный ордер исполнен",
                "Ваш лимитный ордер исполнен — средства зачислены на баланс.",
                node.toString()
        );
        log.info("Created limit-order filled notification for user {}", userId);
    }
}
