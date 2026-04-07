package com.sber.dlmm.fee.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Value("${dlmm.token-service.url:http://localhost:8082}")
    private String tokenServiceUrl;

    @Bean
    public WebClient tokenServiceClient(WebClient.Builder builder) {
        return builder
                .baseUrl(tokenServiceUrl)
                .build();
    }
}
