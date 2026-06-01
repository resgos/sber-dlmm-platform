package com.sber.dlmm.notification.health;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Reports Kafka consumer-group lag for the notification-service's group.
 * Surfaced under {@code /actuator/health/kafkaConsumerLag}. Marks DOWN
 * when total lag across all subscribed partitions exceeds
 * {@link #LAG_DOWN_THRESHOLD}.
 *
 * Why not lean on the Spring Kafka built-in indicator?  spring-kafka's
 * {@code KafkaHealthIndicator} only checks broker reachability — it
 * doesn't tell you whether the consumer is actually keeping up. Lag is
 * the signal that actually matters for "are notifications being
 * delivered in time", which is what an on-call engineer needs to know.
 *
 * Probe budget is tight (3s) — admin calls go to the controller and
 * occasionally rebalance, so we don't want a slow Kafka to stall the
 * service's own /actuator/health response.
 */
@Component
public class KafkaConsumerLagHealthIndicator implements HealthIndicator, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerLagHealthIndicator.class);
    /** Total lag across all subscribed partitions before flipping to DOWN. */
    private static final long LAG_DOWN_THRESHOLD = 1000L;
    /** Per-call timeout (seconds) for each admin probe, keeping the health endpoint snappy. */
    private static final long PROBE_TIMEOUT_SEC = 3L;

    /** Long-lived Kafka {@link AdminClient} used to read committed and end offsets; closed on shutdown. */
    private final AdminClient adminClient;
    /** Consumer group whose lag is measured ({@code dlmm-notification-service}). */
    private final String groupId;

    /**
     * Constructs the indicator and eagerly creates a dedicated {@link AdminClient}.
     *
     * <p>The admin client is configured with deliberately short request/API timeouts so a
     * wedged or slow broker can never hold the {@code /actuator/health} probe open. The client
     * is held for the lifetime of the bean and released in {@link #destroy()}.
     *
     * @param bootstrapServers Kafka broker list, injected from {@code spring.kafka.bootstrap-servers}
     * @param groupId          consumer group id to measure, from {@code spring.kafka.consumer.group-id}
     */
    public KafkaConsumerLagHealthIndicator(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${spring.kafka.consumer.group-id}") String groupId) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // Short timeouts — we don't want a wedged broker to hold this probe.
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 3000);
        this.adminClient = AdminClient.create(props);
        this.groupId = groupId;
    }

    /**
     * Computes current consumer lag and reports it as a Spring Boot {@link Health} status.
     *
     * <p>Algorithm: (1) read the group's committed offset per partition; if none is assigned
     * the consumer is idle (not lagging) and reports UP; (2) read the latest (end) offset of
     * each of those partitions; (3) sum {@code end − committed} across all partitions. The
     * status is UP while the total lag stays at or below {@link #LAG_DOWN_THRESHOLD} and DOWN
     * otherwise, and the details expose the group, total lag, threshold and per-partition lag
     * for debugging. Any probe failure (broker unreachable, timeout) is caught, logged at WARN
     * and reported as DOWN rather than propagated — a health check must never throw.
     *
     * @return a {@link Health} of UP (idle or within threshold) or DOWN (over threshold or
     *         probe failed), carrying diagnostic details
     */
    @Override
    public Health health() {
        try {
            // 1. Where is the consumer? Get its committed offsets per partition.
            ListConsumerGroupOffsetsResult offsetsResult =
                    adminClient.listConsumerGroupOffsets(groupId);
            Map<TopicPartition, OffsetAndMetadata> committed = offsetsResult
                    .partitionsToOffsetAndMetadata()
                    .get(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS);

            if (committed == null || committed.isEmpty()) {
                // No partitions assigned yet — could mean the consumer hasn't started
                // (cold app) or hasn't subscribed (no listener active). Either way
                // it's not "lagging", it's "idle".
                return Health.up()
                        .withDetail("group", groupId)
                        .withDetail("state", "no partitions assigned (idle)")
                        .build();
            }

            // 2. Where is the end of each partition? Latest offsets.
            Map<TopicPartition, OffsetSpec> partitionsToQuery = committed.keySet().stream()
                    .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
            Map<TopicPartition, Long> endOffsets = adminClient
                    .listOffsets(partitionsToQuery)
                    .all()
                    .get(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS)
                    .entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> e.getValue().offset()));

            // 3. Sum lag across all partitions; record per-partition for debugging.
            long totalLag = 0;
            Map<String, Long> perPartitionLag = new HashMap<>();
            for (Map.Entry<TopicPartition, OffsetAndMetadata> e : committed.entrySet()) {
                TopicPartition tp = e.getKey();
                long lag = Math.max(0L, endOffsets.getOrDefault(tp, 0L) - e.getValue().offset());
                totalLag += lag;
                perPartitionLag.put(tp.topic() + "#" + tp.partition(), lag);
            }

            Health.Builder builder = (totalLag <= LAG_DOWN_THRESHOLD ? Health.up() : Health.down())
                    .withDetail("group", groupId)
                    .withDetail("totalLag", totalLag)
                    .withDetail("threshold", LAG_DOWN_THRESHOLD)
                    .withDetail("perPartitionLag", perPartitionLag);

            return builder.build();
        } catch (Exception ex) {
            log.warn("Kafka consumer lag probe failed: {}", ex.toString());
            return Health.down(ex)
                    .withDetail("group", groupId)
                    .build();
        }
    }

    /**
     * Releases the Kafka {@link AdminClient} when the bean is destroyed.
     *
     * <p>Invoked by Spring via {@link DisposableBean} during context shutdown so the admin
     * client's network connections and threads are closed cleanly (avoiding a resource leak).
     */
    @Override
    public void destroy() {
        if (adminClient != null) {
            adminClient.close();
        }
    }
}
