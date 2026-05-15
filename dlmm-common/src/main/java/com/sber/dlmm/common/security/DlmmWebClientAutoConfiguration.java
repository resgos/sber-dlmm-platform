package com.sber.dlmm.common.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Installs {@link BearerTokenForwardingFilter} on every {@code WebClient.Builder}
 * bean — service-to-service WebClient calls automatically carry the inbound
 * caller's Bearer token. Without this, downstream services protected by JWT
 * reject the call with 403.
 *
 * Separated from {@link DlmmJwtAutoConfiguration} so services without
 * spring-webflux on the classpath (e.g. user-service, which has no outbound
 * WebClient calls) don't fail bean introspection on the missing
 * {@code WebClient.Builder} class.
 */
@AutoConfiguration
@ConditionalOnClass(WebClient.class)
public class DlmmWebClientAutoConfiguration {

    @Bean
    public WebClientCustomizer dlmmBearerForwardingCustomizer() {
        return builder -> builder.filter(BearerTokenForwardingFilter.create());
    }
}
