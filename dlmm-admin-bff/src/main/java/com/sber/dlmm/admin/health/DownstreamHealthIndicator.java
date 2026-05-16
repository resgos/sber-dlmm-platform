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
 * Probe budget is intentionally tight (1s) so the parent /actuator/health
 * stays snappy even if one downstream wedges — admin-bff fans out to five
 * services and we don't want the whole probe to stall on a single slow one.
 */
public class DownstreamHealthIndicator implements HealthIndicator {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(1);

    private final String name;
    private final String baseUrl;
    private final WebClient webClient;

    public DownstreamHealthIndicator(String name, String baseUrl) {
        this.name = name;
        this.baseUrl = baseUrl;
        // Dedicated WebClient with no inbound Authorization forwarding —
        // probes shouldn't depend on the caller's session.
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

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
