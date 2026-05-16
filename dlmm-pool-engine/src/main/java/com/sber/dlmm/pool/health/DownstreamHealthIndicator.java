package com.sber.dlmm.pool.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Pings a downstream service's /actuator/health endpoint and reports
 * UP if it returns within {@link #PROBE_TIMEOUT}, DOWN otherwise.
 *
 * Registered once per downstream (token-service, user-service) via
 * {@link com.sber.dlmm.pool.config.HealthConfig}. The contributor name
 * shows up as a section under /actuator/health, so operators can see
 * exactly which downstream is failing without log-grepping.
 *
 * Probe is intentionally short (1s) so the parent /actuator/health
 * stays snappy even if one downstream wedges — k8s liveness probes
 * typically have a 5-10s budget for the whole check.
 */
public class DownstreamHealthIndicator implements HealthIndicator {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(1);

    private final String name;
    private final String baseUrl;
    private final WebClient webClient;

    public DownstreamHealthIndicator(String name, String baseUrl) {
        this.name = name;
        this.baseUrl = baseUrl;
        // Build a dedicated WebClient — we deliberately do NOT use the
        // app-wide WebClient.Builder, because it has the
        // BearerTokenForwardingFilter applied which would attach the
        // inbound caller's Authorization header to probes. Health probes
        // are anonymous (actuator is permitAll), so leaving the header
        // off keeps them independent of any user session.
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
