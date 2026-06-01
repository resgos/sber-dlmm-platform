package com.sber.dlmm.fee.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Sprint 8 #C-10 — renamed the WebClient bean from {@code tokenServiceClient}
 * to {@code tokenServiceWebClient} to resolve a naming clash with the new
 * {@link com.sber.dlmm.fee.client.TokenServiceClient @Component} which
 * wraps the same WebClient in a @CircuitBreaker-annotated facade. The
 * Component injects this bean by its new name.
 */
@Configuration
public class WebClientConfig {

    /** Base URL of token-service, from {@code dlmm.token-service.url} (defaults to localhost:8082 for local runs). */
    @Value("${dlmm.token-service.url:http://localhost:8082}")
    private String tokenServiceUrl;

    /**
     * The pre-configured {@link WebClient} that {@link com.sber.dlmm.fee.client.TokenServiceClient}
     * uses to call token-service's internal credit endpoint. Bean name {@code tokenServiceWebClient}
     * is significant — the resilience-wrapped {@code @Component} injects it by that exact name to
     * avoid the clash described on the class. Built from the shared
     * {@code WebClient.Builder} so cross-cutting filters (e.g. inbound bearer-token forwarding)
     * are applied, with only the base URL pinned here.
     *
     * @param builder the auto-configured shared {@link WebClient.Builder} (carries common filters)
     * @return a {@link WebClient} based at the token-service URL
     */
    @Bean
    public WebClient tokenServiceWebClient(WebClient.Builder builder) {
        return builder
                .baseUrl(tokenServiceUrl)
                .build();
    }
}
