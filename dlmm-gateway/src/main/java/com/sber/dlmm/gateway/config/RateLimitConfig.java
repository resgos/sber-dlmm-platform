package com.sber.dlmm.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Objects;

@Configuration
public class RateLimitConfig {

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId != null && !userId.isBlank()) {
                return Mono.just(userId);
            }
            InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
            String hostAddress = remoteAddress != null
                    ? Objects.requireNonNull(remoteAddress.getAddress()).getHostAddress()
                    : "unknown";
            return Mono.just(hostAddress);
        };
    }
}
