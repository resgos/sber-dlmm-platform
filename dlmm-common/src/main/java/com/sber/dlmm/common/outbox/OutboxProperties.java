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

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public long getDispatchIntervalMs() { return dispatchIntervalMs; }
    public void setDispatchIntervalMs(long dispatchIntervalMs) { this.dispatchIntervalMs = dispatchIntervalMs; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public long getSendTimeoutSec() { return sendTimeoutSec; }
    public void setSendTimeoutSec(long sendTimeoutSec) { this.sendTimeoutSec = sendTimeoutSec; }
}
