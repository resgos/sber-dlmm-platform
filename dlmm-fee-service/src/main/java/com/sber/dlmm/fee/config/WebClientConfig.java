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

    @Value("${dlmm.token-service.url:http://localhost:8082}")
    private String tokenServiceUrl;

    @Bean
    public WebClient tokenServiceWebClient(WebClient.Builder builder) {
        return builder
                .baseUrl(tokenServiceUrl)
                .build();
    }
}
