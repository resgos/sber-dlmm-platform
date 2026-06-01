package com.sber.dlmm.pool.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Both key and value are {@code String}: payloads come pre-serialised
 * from the transactional outbox (Jackson runs once in
 * {@code OutboxService.append}), so the Kafka layer just forwards the
 * bytes. Using JsonSerializer here would double-encode the JSON (wrap
 * the already-stringified payload in quotes), breaking every consumer.
 *
 * <p>Spring config that supplies the {@code String}/{@code String} Kafka
 * producer used by the outbox dispatcher to publish pool-engine domain events.
 */
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * Producer factory wired to the configured broker(s) with String
     * serializers on both key and value — see the class note for why values
     * are sent as already-serialised JSON strings rather than re-serialised.
     *
     * @return a {@link ProducerFactory} producing String-keyed, String-valued records
     */
    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(config);
    }

    /**
     * The {@link KafkaTemplate} the application injects to publish events,
     * backed by {@link #producerFactory()}.
     *
     * @return a String-keyed, String-valued {@link KafkaTemplate}
     */
    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
