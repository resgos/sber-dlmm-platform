package com.sber.dlmm.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Auto-configures the shared transactional outbox (Sprint 3 #3.9).
 *
 * Activates when:
 *   - JPA is on the classpath ({@link EntityManagerFactory})
 *   - Kafka is on the classpath ({@link KafkaTemplate})
 *   - The service explicitly opts in by setting
 *     {@code dlmm.outbox.service-name=<name>} in their YAML.
 *
 * Entity + repository discovery: each consuming service must extend
 * {@code @EntityScan} and {@code @EnableJpaRepositories} on its
 * {@code @SpringBootApplication} class to include
 * {@code com.sber.dlmm.common.outbox} alongside its own package.
 * Documented in {@code docs/DB-MIGRATION-CONVENTION.md} and in the
 * {@code Application.java} of each consuming service.
 *
 * We don't try to register packages programmatically (e.g. via
 * AutoConfigurationPackages) because Spring Boot's
 * JpaRepositoriesAutoConfiguration reads its package list at
 * BeanDefinitionRegistry-population time, before our auto-config can
 * inject. The explicit annotation route is simpler and impossible to
 * forget — Spring fails at startup with "no bean of type
 * OutboxEventRepository" if the consumer doesn't extend the scan.
 */
@AutoConfiguration
@AutoConfigureAfter(JpaRepositoriesAutoConfiguration.class)
@ConditionalOnClass({EntityManagerFactory.class, KafkaTemplate.class})
@ConditionalOnProperty(name = "dlmm.outbox.service-name")
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
public class DlmmOutboxAutoConfiguration {

    /**
     * The append-side bean domain code calls inside its business transaction.
     *
     * @param repository   outbox row repository
     * @param objectMapper Jackson mapper for payload serialisation
     * @param properties   outbox config (supplies the service-name tag)
     * @return the singleton {@link OutboxService}
     */
    @Bean
    public OutboxService outboxService(OutboxEventRepository repository,
                                       ObjectMapper objectMapper,
                                       OutboxProperties properties) {
        return new OutboxService(repository, objectMapper, properties);
    }

    /**
     * The scheduled drain-side bean that ships this service's rows to Kafka.
     *
     * @param repository    outbox row repository
     * @param kafkaTemplate Kafka producer used to publish payloads
     * @param properties    outbox config (service name, batch size, timeouts, retention)
     * @return the singleton {@link OutboxDispatcher}
     */
    @Bean
    public OutboxDispatcher outboxDispatcher(OutboxEventRepository repository,
                                              KafkaTemplate<String, String> kafkaTemplate,
                                              OutboxProperties properties) {
        return new OutboxDispatcher(repository, kafkaTemplate, properties);
    }
}
