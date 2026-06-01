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

    /**
     * Registers the {@code user-service} health contributor. Spring names the
     * actuator section after the bean, so this surfaces at
     * {@code /actuator/health/userServiceHealthIndicator} (and folds into the
     * aggregate status) — giving the admin dashboard a per-dependency dot.
     *
     * @param url user-service base URL, injected from {@code dlmm.services.user-service-url}
     * @return a {@link DownstreamHealthIndicator} probing user-service's {@code /actuator/health}
     */
    @Bean
    public HealthIndicator userServiceHealthIndicator(
            @Value("${dlmm.services.user-service-url}") String url) {
        return new DownstreamHealthIndicator("user-service", url);
    }

    /**
     * Registers the {@code token-service} health contributor (see
     * {@link #userServiceHealthIndicator} for the actuator-section mechanics).
     *
     * @param url token-service base URL, injected from {@code dlmm.services.token-service-url}
     * @return a {@link DownstreamHealthIndicator} probing token-service's {@code /actuator/health}
     */
    @Bean
    public HealthIndicator tokenServiceHealthIndicator(
            @Value("${dlmm.services.token-service-url}") String url) {
        return new DownstreamHealthIndicator("token-service", url);
    }

    /**
     * Registers the {@code pool-engine} health contributor. Pool-engine is the
     * dashboard's hottest dependency, so its dot is the one operators watch most
     * (see {@link #userServiceHealthIndicator} for the actuator-section mechanics).
     *
     * @param url pool-engine base URL, injected from {@code dlmm.services.pool-engine-url}
     * @return a {@link DownstreamHealthIndicator} probing pool-engine's {@code /actuator/health}
     */
    @Bean
    public HealthIndicator poolEngineHealthIndicator(
            @Value("${dlmm.services.pool-engine-url}") String url) {
        return new DownstreamHealthIndicator("pool-engine", url);
    }

    /**
     * Registers the {@code transaction-service} health contributor (see
     * {@link #userServiceHealthIndicator} for the actuator-section mechanics).
     *
     * @param url transaction-service base URL, injected from {@code dlmm.services.transaction-service-url}
     * @return a {@link DownstreamHealthIndicator} probing transaction-service's {@code /actuator/health}
     */
    @Bean
    public HealthIndicator transactionServiceHealthIndicator(
            @Value("${dlmm.services.transaction-service-url}") String url) {
        return new DownstreamHealthIndicator("transaction-service", url);
    }

    /**
     * Registers the {@code fee-service} health contributor (see
     * {@link #userServiceHealthIndicator} for the actuator-section mechanics).
     *
     * @param url fee-service base URL, injected from {@code dlmm.services.fee-service-url}
     * @return a {@link DownstreamHealthIndicator} probing fee-service's {@code /actuator/health}
     */
    @Bean
    public HealthIndicator feeServiceHealthIndicator(
            @Value("${dlmm.services.fee-service-url}") String url) {
        return new DownstreamHealthIndicator("fee-service", url);
    }
}
