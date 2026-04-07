package com.sber.dlmm.user.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaProducerService {

    private static final String TOPIC = "user-events";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void sendUserCreated(UserCreatedEvent event) {
        log.info("Sending user.created event for userId={}", event.userId());
        kafkaTemplate.send(TOPIC, event.userId().toString(), event);
    }

    public void sendUserKycVerified(UserKycVerifiedEvent event) {
        log.info("Sending user.kyc.verified event for userId={}", event.userId());
        kafkaTemplate.send(TOPIC, event.userId().toString(), event);
    }

    public void sendUserBlocked(UserBlockedEvent event) {
        log.info("Sending user.blocked event for userId={}", event.userId());
        kafkaTemplate.send(TOPIC, event.userId().toString(), event);
    }
}
