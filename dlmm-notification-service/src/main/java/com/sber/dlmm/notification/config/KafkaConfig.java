package com.sber.dlmm.notification.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer wiring for the notification-service.
 *
 * <p>Defines the {@link ConsumerFactory} and the listener container factory that back
 * every {@code @KafkaListener} in this service. Both the key and the value are read as
 * raw {@link String}s (JSON) — deserialization into typed events is done explicitly by
 * the listener via Jackson, which keeps the consumer tolerant of schema drift across the
 * many producing services (an unknown field never fails deserialization at the Kafka
 * layer).
 *
 * <p>{@code auto-offset-reset=earliest} ensures that a freshly provisioned consumer group
 * replays the backlog from the start rather than silently skipping events that were
 * published before it first subscribed.
 */
@Configuration
public class KafkaConfig {

    /** Comma-separated Kafka broker list, injected from {@code spring.kafka.bootstrap-servers}. */
    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /** Consumer group id ({@code dlmm-notification-service}) that owns this service's offsets/lag. */
    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    /**
     * Builds the low-level Kafka {@link ConsumerFactory} shared by all listener containers.
     *
     * <p>Uses {@link StringDeserializer} for both key and value (events are consumed as raw
     * JSON strings and parsed downstream) and resets to the earliest offset for a brand-new
     * group so no historical event is missed.
     *
     * @return a configured {@code ConsumerFactory} producing {@code String}-keyed,
     *         {@code String}-valued consumers
     */
    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Provides the {@link ConcurrentKafkaListenerContainerFactory} that Spring Kafka uses to
     * create a container for each {@code @KafkaListener} method, wired to {@link #consumerFactory()}.
     *
     * <p>The bean name {@code kafkaListenerContainerFactory} is the Spring Kafka default, so
     * listeners pick it up without an explicit {@code containerFactory} attribute.
     *
     * @return the listener container factory backing all Kafka listeners in this service
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        return factory;
    }
}
