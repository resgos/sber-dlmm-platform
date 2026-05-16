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
    private static final long PROBE_TIMEOUT_SEC = 3L;

    private final AdminClient adminClient;
    private final String groupId;

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

    @Override
    public void destroy() {
        if (adminClient != null) {
            adminClient.close();
        }
    }
}
