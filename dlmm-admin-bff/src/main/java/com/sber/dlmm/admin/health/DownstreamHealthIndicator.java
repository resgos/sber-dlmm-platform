package com.sber.dlmm.admin.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Pings a downstream service's /actuator/health endpoint and reports
 * UP if it returns within {@link #PROBE_TIMEOUT}, DOWN otherwise.
 *
 * The contributor name shows up as a section under {@code /actuator/health},
 * so operators can see exactly which downstream is failing without
 * grepping logs.
 *
 * Probe budget — bumped from 1s to 3s on 2026-05-19. With JVM heap caps
 * + cold start after rebuild, the actuator-health round-trip on
 * pool-engine routinely takes 1.2-2s (Hibernate session boot + Redis
 * Lettuce handshake during the probe). 1s mis-classified pool-engine
 * as DOWN, which cascaded into admin-bff DOWN, which made the admin-ui
 * dashboard hang waiting for /admin/dashboard. 3s still keeps the parent
 * /actuator/health under 3s even when 5 downstreams all fail.
 */
public class DownstreamHealthIndicator implements HealthIndicator {

    /** Max round-trip allowed for a downstream {@code /actuator/health} probe before it is treated as DOWN (see class Javadoc for why 3s, not 1s). */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    private final String name;
    private final String baseUrl;
    private final WebClient webClient;

    /**
     * Builds an indicator that probes one downstream service.
     *
     * @param name    short service label (e.g. {@code "pool-engine"}); becomes
     *                the section name under {@code /actuator/health/{name}} and
     *                is echoed back in every probe result's {@code service} detail
     * @param baseUrl base URL of the downstream service; {@code /actuator/health}
     *                is appended to it for the probe
     */
    public DownstreamHealthIndicator(String name, String baseUrl) {
        this.name = name;
        this.baseUrl = baseUrl;
        // Dedicated WebClient with no inbound Authorization forwarding —
        // probes shouldn't depend on the caller's session.
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * Performs the actual probe: GET {@code {baseUrl}/actuator/health} bounded
     * by {@link #PROBE_TIMEOUT}. Any error (timeout, connection refused, non-2xx)
     * is mapped to an empty response and reported as DOWN rather than propagated,
     * so one wedged downstream marks only its own section red and never throws
     * the aggregate {@code /actuator/health} into a 500.
     *
     * @return {@link Health#up()} with {@code service}/{@code url} details when a
     *         body comes back in time; otherwise {@link Health#down()} carrying
     *         the same details plus a {@code reason} (timeout) or the exception
     */
    @Override
    public Health health() {
        try {
            String body = webClient.get()
                    .uri("/actuator/health")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(PROBE_TIMEOUT)
                    .onErrorResume(ex -> Mono.empty())
                    .block();
            if (body == null) {
                return Health.down()
                        .withDetail("service", name)
                        .withDetail("url", baseUrl)
                        .withDetail("reason", "no response within " + PROBE_TIMEOUT)
                        .build();
            }
            return Health.up()
                    .withDetail("service", name)
                    .withDetail("url", baseUrl)
                    .build();
        } catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("service", name)
                    .withDetail("url", baseUrl)
                    .build();
        }
    }
}
