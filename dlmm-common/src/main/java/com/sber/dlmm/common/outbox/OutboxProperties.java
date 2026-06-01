package com.sber.dlmm.common.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config knobs for the shared transactional outbox.
 *
 * Most important is {@link #serviceName} — each service must set
 * {@code dlmm.outbox.service-name=<their-name>} (e.g. "token-service")
 * so their dispatcher only polls their own rows in the shared
 * {@code outbox_events} table.
 */
@ConfigurationProperties(prefix = "dlmm.outbox")
public class OutboxProperties {

    /**
     * Tag written on every row this service produces. Each service's
     * dispatcher filters by this value. Required — auto-config refuses
     * to start without it (no default would prevent the cross-service
     * race the {@code service} column is there to prevent).
     */
    private String serviceName;

    /**
     * Dispatcher tick interval in ms. 500ms keeps publish latency
     * sub-second under normal load without dominating DB time on
     * idle services.
     */
    private long dispatchIntervalMs = 500L;

    /**
     * How many rows the dispatcher pulls per tick. Bigger batches help
     * throughput on a backlog, smaller batches reduce blast radius if
     * a slow Kafka send stalls.
     */
    private int batchSize = 100;

    /**
     * Per-record Kafka send timeout (seconds). Caps any single send
     * so a wedged broker can't stall the whole tick.
     */
    private long sendTimeoutSec = 3L;

    /**
     * Sprint 9-DS-r4 (P0-5) — published rows older than this many days
     * are deleted by the daily cleanup job. We replay events from
     * Kafka topic offsets, never from the outbox, so retained rows
     * exist only for debug/audit. A week is enough to reconcile any
     * incident; longer would let the table grow unboundedly (1833
     * rows accumulated in 24h of testing).
     *
     * <p>Set to 0 to disable cleanup (useful in dev / tests).
     */
    private int retentionDays = 7;

    /**
     * Cron for the cleanup job. Default 03:17 daily — off-peak,
     * doesn't collide with hourly batch jobs at minute 0.
     */
    private String cleanupCron = "0 17 3 * * *";

    /** @return this service's outbox tag ({@code dlmm.outbox.service-name}). */
    public String getServiceName() { return serviceName; }
    /** @param serviceName this service's outbox tag. */
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    /** @return the dispatcher tick interval in milliseconds. */
    public long getDispatchIntervalMs() { return dispatchIntervalMs; }
    /** @param dispatchIntervalMs the dispatcher tick interval in milliseconds. */
    public void setDispatchIntervalMs(long dispatchIntervalMs) { this.dispatchIntervalMs = dispatchIntervalMs; }
    /** @return the maximum rows pulled per dispatcher tick. */
    public int getBatchSize() { return batchSize; }
    /** @param batchSize the maximum rows to pull per dispatcher tick. */
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    /** @return the per-record Kafka send timeout in seconds. */
    public long getSendTimeoutSec() { return sendTimeoutSec; }
    /** @param sendTimeoutSec the per-record Kafka send timeout in seconds. */
    public void setSendTimeoutSec(long sendTimeoutSec) { this.sendTimeoutSec = sendTimeoutSec; }
    /** @return retention in days for published rows (0 disables cleanup). */
    public int getRetentionDays() { return retentionDays; }
    /** @param retentionDays retention in days for published rows (0 disables cleanup). */
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
    /** @return the cron expression controlling the cleanup job. */
    public String getCleanupCron() { return cleanupCron; }
    /** @param cleanupCron the cron expression controlling the cleanup job. */
    public void setCleanupCron(String cleanupCron) { this.cleanupCron = cleanupCron; }
}
