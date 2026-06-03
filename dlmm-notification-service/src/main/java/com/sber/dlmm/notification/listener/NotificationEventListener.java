package com.sber.dlmm.notification.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sber.dlmm.common.enums.NotificationType;
import com.sber.dlmm.notification.service.NotificationService;
import com.sber.dlmm.notification.service.NotificationFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Kafka consumer that turns raw domain events into user-facing notifications.
 *
 * <p>This is the heart of the notification-service: it subscribes (under the single consumer
 * group {@code dlmm-notification-service}) to three topics and dispatches each message to the
 * right handler purely by inspecting the JSON's <em>shape</em> — events arrive as raw strings
 * (see {@link com.sber.dlmm.notification.config.KafkaConfig}) and are parsed field-by-field
 * with Jackson, so an unrecognized payload is simply ignored rather than failing the consumer.
 *
 * <p>Topics consumed:
 * <ul>
 *   <li>{@code pool-events} — swaps, liquidity adds and limit-order fills (shape-routed via
 *       {@link #determinePoolEventType(JsonNode)}); only fills/swaps/adds yield a notification,
 *       order-placed/cancelled siblings are intentionally dropped.</li>
 *   <li>{@code fee-events} — fee claims → {@code FEE_ACCRUED} notification.</li>
 *   <li>{@code user-events} — KYC approval plus margin-call/-warning events (the latter
 *       re-routed through this topic so there is one notification entry-point per user).</li>
 * </ul>
 *
 * <p><strong>Idempotency / dedup:</strong> handlers are not idempotent on their own — each
 * accepted event creates a new {@code notifications} row. At-least-once delivery is bounded by
 * the consumer committing offsets after a successful poll; on redelivery a duplicate
 * notification could appear. This is tolerated as low-impact (a duplicated in-app message),
 * and a schema-level dedup key remains an explicitly deferred improvement. Every handler wraps
 * its work in try/catch and logs failures so a single poison message can never halt the
 * partition.
 *
 * <p>User-facing copy is intentionally authored in Russian here (this service owns the wording);
 * other services only publish the structured events. The constructor and logger are generated
 * by Lombok ({@code @RequiredArgsConstructor}/{@code @Slf4j}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationEventListener {

    /** Service that persists the crafted notifications. */
    private final NotificationService notificationService;
    /** Jackson mapper used to parse raw event JSON field-by-field (schema-tolerant). */
    private final ObjectMapper objectMapper;
    /** Renders raw amounts + token ids into human copy (symbols, ×10⁴ scale) — audit B2. */
    private final NotificationFormatter formatter;

    /**
     * Consumes the {@code pool-events} topic and fans out by inferred event type.
     *
     * <p>Parses the record value, classifies it via {@link #determinePoolEventType(JsonNode)}
     * (swap / liquidity-add / limit-order-fill), and delegates to the matching handler;
     * unrecognized types are logged at DEBUG and ignored. Any parsing/handling exception is
     * caught and logged at ERROR so the listener keeps consuming subsequent records.
     *
     * @param record the raw Kafka record whose value is the event JSON
     */
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

    /**
     * Consumes the {@code fee-events} topic and notifies users that claimed accrued fees.
     *
     * <p>Recognizes a fee-claim by the presence of both {@code positionId} and {@code claimedX}
     * fields; for those it extracts the user and claimed X/Y amounts and creates a
     * {@code FEE_ACCRUED} notification, storing the raw event JSON as the payload. Other event
     * shapes on this topic are silently skipped. Exceptions are caught and logged at ERROR.
     *
     * @param record the raw Kafka record whose value is the fee event JSON
     */
    @KafkaListener(topics = "fee-events", groupId = "dlmm-notification-service")
    public void handleFeeEvents(ConsumerRecord<String, String> record) {
        try {
            JsonNode node = objectMapper.readTree(record.value());

            if (node.has("positionId") && node.has("claimedX")) {
                UUID userId = UUID.fromString(node.get("userId").asText());
                long claimedX = node.get("claimedX").asLong();
                long claimedY = node.get("claimedY").asLong();
                String tokenXId = textOrNull(node, "tokenXId");
                String tokenYId = textOrNull(node, "tokenYId");

                // Audit B2 — human amounts + token symbols (only the legs actually paid).
                StringBuilder legs = new StringBuilder();
                if (claimedX > 0) legs.append(formatter.amountWithSymbol(claimedX, tokenXId));
                if (claimedY > 0) {
                    if (legs.length() > 0) legs.append(" + ");
                    legs.append(formatter.amountWithSymbol(claimedY, tokenYId));
                }
                if (legs.length() == 0) legs.append("0");

                notificationService.createNotification(
                        userId,
                        NotificationType.FEE_ACCRUED,
                        "Комиссии забраны",
                        "Комиссии забраны: " + legs,
                        record.value()
                );
                log.info("Created fee claimed notification for user {}", userId);
            }
        } catch (Exception e) {
            log.error("Failed to process fee event: {}", record.value(), e);
        }
    }

    /**
     * Consumes the {@code user-events} topic, which carries both KYC approvals and margin events.
     *
     * <p>Margin-call / margin-warning events from the pool-engine are deliberately routed here
     * (one notification entry-point per user) and are distinguished by carrying {@code eventType}
     * together with {@code rangeMin}/{@code rangeMax}; those are delegated to
     * {@link #handleMarginEvent(JsonNode, String)} and processing returns early. Otherwise a
     * record with {@code userId} + {@code email} is treated as a KYC-approved event and produces
     * a {@code KYC_APPROVED} welcome notification (falling back to the name "User" when
     * {@code fullName} is absent). Exceptions are caught and logged at ERROR.
     *
     * @param record the raw Kafka record whose value is the user/margin event JSON
     */
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
     *
     * <p>Reads the position, active bin, range bounds, distance-from-boundary and optional
     * rebalance deadline from the event, then composes a different Russian title/message for
     * {@code MARGIN_CALL} (out of range — fees stop accruing) versus {@code MARGIN_WARNING}
     * (approaching the boundary). The {@code eventType} field is mapped straight onto the
     * {@link NotificationType} enum, so it must name a valid constant.
     *
     * @param node       parsed margin event JSON (must contain {@code eventType}, {@code userId},
     *                   {@code positionId}, {@code activeBinId}, {@code rangeMin}, {@code rangeMax}
     *                   and {@code distanceFromBoundary}; {@code rebalanceDeadline} is optional)
     * @param rawPayload the original event JSON string, stored verbatim as the notification payload
     * @throws IllegalArgumentException if {@code eventType} is not a valid {@link NotificationType}
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

    /**
     * Infers which pool event a JSON payload represents by probing for its distinguishing fields.
     *
     * <p>Because {@code pool-events} carries several event types as untyped JSON, classification
     * is done by field presence (there is no explicit type discriminator on these events):
     * {@code limitOrderId} + {@code fillPrice} → a fill; {@code tokenInId} + {@code amountIn} +
     * {@code fee} → a swap; {@code positionId} + {@code amountX} + {@code amountY} and <em>no</em>
     * {@code fee} → a liquidity add. Order-placed/cancelled siblings (which lack {@code fillPrice})
     * and anything else fall through to {@code "Unknown"} and are ignored by the caller — that is
     * intentional, only fills/swaps/adds warrant a user notification.
     *
     * @param node the parsed pool-event JSON
     * @return one of {@code "LimitOrderFilledEvent"}, {@code "SwapExecutedEvent"},
     *         {@code "LiquidityAddedEvent"}, or {@code "Unknown"}
     */
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

    /**
     * Creates a {@code SWAP_COMPLETED} notification for an executed swap.
     *
     * <p>Extracts the user and the in/out amounts and records a Russian confirmation summarizing
     * the trade, storing the event JSON as the payload.
     *
     * @param node parsed {@code SwapExecutedEvent} JSON (expects {@code userId}, {@code amountIn},
     *             {@code amountOut})
     */
    private void handleSwapExecuted(JsonNode node) {
        UUID userId = UUID.fromString(node.get("userId").asText());
        long amountIn = node.get("amountIn").asLong();
        long amountOut = node.get("amountOut").asLong();
        String tokenInId = textOrNull(node, "tokenInId");
        String tokenOutId = textOrNull(node, "tokenOutId");

        // Audit B2 — human amounts + token symbols instead of raw integers.
        notificationService.createNotification(
                userId,
                NotificationType.SWAP_COMPLETED,
                "Своп выполнен",
                String.format("Своп выполнен: %s → %s",
                        formatter.amountWithSymbol(amountIn, tokenInId),
                        formatter.amountWithSymbol(amountOut, tokenOutId)),
                node.toString()
        );
        log.info("Created swap notification for user {}", userId);
    }

    /**
     * Creates a notification confirming that liquidity was added to a pool.
     *
     * <p>Extracts the user, pool and deposited X/Y amounts and records a Russian confirmation,
     * storing the event JSON as the payload. Categorized as {@code SYSTEM_ALERT} (there is no
     * dedicated liquidity-add notification type).
     *
     * @param node parsed {@code LiquidityAddedEvent} JSON (expects {@code userId}, {@code poolId},
     *             {@code amountX}, {@code amountY})
     */
    private void handleLiquidityAdded(JsonNode node) {
        UUID userId = UUID.fromString(node.get("userId").asText());
        UUID poolId = UUID.fromString(node.get("poolId").asText());
        long amountX = node.get("amountX").asLong();
        long amountY = node.get("amountY").asLong();

        // Audit B2 — pool pair label + human amounts + symbols (the event carries no
        // token ids, so resolve the pool's X/Y symbols once from the shared catalog).
        String[] sym = formatter.poolTokenSymbols(poolId.toString());
        notificationService.createNotification(
                userId,
                NotificationType.SYSTEM_ALERT,
                "Ликвидность добавлена",
                String.format("Ликвидность добавлена в пул %s/%s: %s %s + %s %s",
                        sym[0], sym[1],
                        formatter.amount(amountX), sym[0], formatter.amount(amountY), sym[1]),
                node.toString()
        );
        log.info("Created liquidity added notification for user {}", userId);
    }

    /** Reads a string field, returning {@code null} when absent or JSON null. */
    private static String textOrNull(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    /**
     * Sprint 16 (Meteora parity) — a limit order filled (possibly long after it
     * was placed), so the user must be told. Reuses SWAP_COMPLETED (a fill is an
     * executed trade) to avoid a cross-service NotificationType enum change.
     *
     * <p>Creates a fixed Russian "limit order executed — funds credited" notification for the
     * user, storing the event JSON as the payload.
     *
     * @param node parsed {@code LimitOrderFilledEvent} JSON (expects {@code userId})
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
