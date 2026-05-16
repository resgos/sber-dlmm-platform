package com.sber.dlmm.admin.config;

import com.sber.dlmm.admin.health.DownstreamHealthIndicator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One {@link HealthIndicator} bean per downstream service. Bean names
 * become section names under {@code /actuator/health/{name}} so the
 * admin dashboard can show a green/red dot per dependency without any
 * extra wiring.
 *
 * Price-oracle is intentionally excluded — it has no /actuator/health
 * (it's effectively a stub feed in this demo) and probing it would
 * always mark the BFF DOWN.
 */
@Configuration
public class HealthConfig {

    @Bean
    public HealthIndicator userServiceHealthIndicator(
            @Value("${dlmm.services.user-service-url}") String url) {
        return new DownstreamHealthIndicator("user-service", url);
    }

    @Bean
    public HealthIndicator tokenServiceHealthIndicator(
            @Value("${dlmm.services.token-service-url}") String url) {
        return new DownstreamHealthIndicator("token-service", url);
    }

    @Bean
    public HealthIndicator poolEngineHealthIndicator(
            @Value("${dlmm.services.pool-engine-url}") String url) {
        return new DownstreamHealthIndicator("pool-engine", url);
    }

    @Bean
    public HealthIndicator transactionServiceHealthIndicator(
            @Value("${dlmm.services.transaction-service-url}") String url) {
        return new DownstreamHealthIndicator("transaction-service", url);
    }

    @Bean
    public HealthIndicator feeServiceHealthIndicator(
            @Value("${dlmm.services.fee-service-url}") String url) {
        return new DownstreamHealthIndicator("fee-service", url);
    }
}
