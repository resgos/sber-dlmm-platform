package com.sber.dlmm.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the DLMM API gateway.
 *
 * <p>This is the {@code dlmm-gateway} module's bootstrap class — the single
 * public ingress for the whole platform. It runs as a reactive Spring Cloud
 * Gateway (Netty/WebFlux, not servlet) and fronts all 8 domain/BFF services
 * plus the two SPAs. Every external request lands here first and is subject,
 * in filter order, to: request logging + trace-id stamping
 * ({@link com.sber.dlmm.gateway.filter.RequestLoggingFilter}, order -200),
 * JWT validation + trusted-header injection
 * ({@link com.sber.dlmm.gateway.filter.JwtValidationFilter}, order -100),
 * and per-tier Redis rate limiting
 * ({@link com.sber.dlmm.gateway.filter.TierBasedRateLimitFilter}, order -50),
 * before being proxied to the upstream service per the route table in
 * {@code application.yml}.
 *
 * <p>{@code @SpringBootApplication} triggers component scanning of this
 * package, so the gateway's {@code @Component} filters and
 * {@code @Configuration} beans (CORS, rate-limit) are picked up automatically,
 * along with the auto-configured cross-cutting infra inherited from
 * {@code dlmm-common} (JWT helpers, secret-validation-on-startup, etc.).
 */
@SpringBootApplication
public class DlmmGatewayApplication {

    /**
     * JVM entry point — boots the reactive gateway.
     *
     * <p>Delegates to {@link SpringApplication#run(Class, String[])}, which
     * starts the embedded reactive (Netty) server, performs component scanning,
     * and wires every filter and config bean in this module. The process keeps
     * running until the context is shut down; this is the only {@code main}
     * in the module, so {@code java -jar dlmm-gateway.jar} lands here.
     *
     * @param args standard JVM command-line arguments, forwarded verbatim to
     *             Spring Boot so the usual {@code --property=value} and
     *             {@code --spring.profiles.active=...} overrides work
     */
    public static void main(String[] args) {
        SpringApplication.run(DlmmGatewayApplication.class, args);
    }
}
