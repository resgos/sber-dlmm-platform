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
 * Probe budget — bumped from 1s to 3s on 2026-05-19, see twin in
 * admin-bff for the rationale (cold-start Hibernate + Lettuce round-trip
 * routinely takes 1.5s and we don't want spurious DOWN cascades).
 */
public class DownstreamHealthIndicator implements HealthIndicator {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    /** Human label for this downstream (e.g. "token-service"); shown in health details. */
    private final String name;
    /** Base URL of the downstream whose {@code /actuator/health} is probed. */
    private final String baseUrl;
    /** Dedicated, filter-free client used only for the anonymous probe. */
    private final WebClient webClient;

    /**
     * Creates a health contributor for one downstream service. Builds a
     * dedicated {@link WebClient} (see inline note) rather than reusing the
     * app-wide builder, so the probe carries no inbound Authorization header.
     *
     * @param name    label reported under {@code service} in the health detail
     * @param baseUrl downstream base URL; {@code /actuator/health} is appended
     */
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

    /**
     * Probes the downstream's {@code /actuator/health} once.
     *
     * <p>Returns {@link Health#up()} if a (non-null) response body comes back
     * within {@link #PROBE_TIMEOUT}; otherwise {@link Health#down()} — both with
     * {@code service}/{@code url} details, and DOWN additionally carrying a
     * {@code reason} (timeout/no-response) or the exception. The downstream's
     * own UP/DOWN payload is not parsed: any timely reply counts as reachable.
     * Errors are swallowed into DOWN so this contributor never throws into the
     * aggregate health endpoint.
     *
     * @return UP when the downstream answered in time, DOWN otherwise
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
