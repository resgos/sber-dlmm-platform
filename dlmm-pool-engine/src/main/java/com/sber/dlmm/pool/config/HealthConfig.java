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

    @Bean
    public HealthIndicator tokenServiceHealthIndicator(
            @Value("${dlmm.services.token-service.url}") String tokenServiceUrl) {
        return new DownstreamHealthIndicator("token-service", tokenServiceUrl);
    }

    @Bean
    public HealthIndicator userServiceHealthIndicator(
            @Value("${dlmm.services.user-service.url}") String userServiceUrl) {
        return new DownstreamHealthIndicator("user-service", userServiceUrl);
    }
}
