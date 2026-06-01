package com.sber.dlmm.pool.config;

import com.sber.dlmm.pool.health.DownstreamHealthIndicator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers per-downstream {@link HealthIndicator} beans. Bean names
 * become section names under {@code /actuator/health/{name}}, so
 * {@code tokenService} below surfaces as {@code health.components.tokenService}.
 */
@Configuration
public class HealthConfig {

    /**
     * Health indicator that probes dlmm-token-service. Registered under the
     * bean name {@code tokenServiceHealthIndicator}, so it appears as
     * {@code health.components.tokenService} in the actuator health report and
     * lets ops see at a glance whether this critical dependency is reachable.
     *
     * @param tokenServiceUrl token-service base URL (from {@code dlmm.services.token-service.url})
     * @return a {@link HealthIndicator} that reports token-service reachability
     */
    @Bean
    public HealthIndicator tokenServiceHealthIndicator(
            @Value("${dlmm.services.token-service.url}") String tokenServiceUrl) {
        return new DownstreamHealthIndicator("token-service", tokenServiceUrl);
    }

    /**
     * Health indicator that probes dlmm-user-service. Registered under the
     * bean name {@code userServiceHealthIndicator}, so it appears as
     * {@code health.components.userService} in the actuator health report —
     * relevant because the KYC lookup path depends on user-service.
     *
     * @param userServiceUrl user-service base URL (from {@code dlmm.services.user-service.url})
     * @return a {@link HealthIndicator} that reports user-service reachability
     */
    @Bean
    public HealthIndicator userServiceHealthIndicator(
            @Value("${dlmm.services.user-service.url}") String userServiceUrl) {
        return new DownstreamHealthIndicator("user-service", userServiceUrl);
    }
}
