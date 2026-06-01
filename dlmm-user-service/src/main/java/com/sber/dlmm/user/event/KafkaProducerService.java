package com.sber.dlmm.user.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Thin publisher that emits user-lifecycle domain events onto the single
 * {@code user-events} Kafka topic. One send method per event type; all key
 * the message by the user's id (as a String) so every event for a given user
 * lands on the same partition and is delivered in causal order to consumers
 * (notification-service, analytics).
 *
 * <p>Fire-and-forget: these calls do not block on broker acknowledgement and
 * are invoked from inside the issuing service's transaction
 * ({@code UserService}). This is the legacy direct-to-Kafka path; the
 * platform's stronger delivery guarantee (the transactional outbox in
 * {@code dlmm-common}) is not used here, so an event can in principle be lost
 * if the broker is unreachable at send time. Acceptable for these
 * non-financial notifications.
 *
 * <p>The {@code TOPIC} constant must match the topic provisioned in
 * {@code docker/init-kafka-topics.sh}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaProducerService {

    private static final String TOPIC = "user-events";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Publish a {@link UserCreatedEvent} after a new account is registered.
     *
     * @param event the registration event; its {@code userId} is used as the
     *              Kafka message key for partition ordering
     */
    public void sendUserCreated(UserCreatedEvent event) {
        log.info("Sending user.created event for userId={}", event.userId());
        kafkaTemplate.send(TOPIC, event.userId().toString(), event);
    }

    /**
     * Publish a {@link UserKycVerifiedEvent} when a user's KYC reaches
     * VERIFIED, so downstream consumers can unlock KYC-gated features.
     *
     * @param event the KYC-verified event; its {@code userId} is used as the
     *              Kafka message key for partition ordering
     */
    public void sendUserKycVerified(UserKycVerifiedEvent event) {
        log.info("Sending user.kyc.verified event for userId={}", event.userId());
        kafkaTemplate.send(TOPIC, event.userId().toString(), event);
    }

    /**
     * Publish a {@link UserBlockedEvent} after an admin blocks a user.
     *
     * @param event the block event; its {@code userId} is used as the Kafka
     *              message key for partition ordering
     */
    public void sendUserBlocked(UserBlockedEvent event) {
        log.info("Sending user.blocked event for userId={}", event.userId());
        kafkaTemplate.send(TOPIC, event.userId().toString(), event);
    }
}
