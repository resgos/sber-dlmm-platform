package com.sber.dlmm.pool;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @EntityScan and @EnableJpaRepositories list the local package PLUS
 * com.sber.dlmm.common.outbox (Sprint 3 #3.9 shared outbox lib) and
 * com.sber.dlmm.common.audit (Sprint 9-DS-r4 P2-13 shared admin audit).
 * Without these extensions Spring Boot's default scan only finds
 * entities/repos in com.sber.dlmm.pool and the shared library
 * components are invisible to the JPA / Spring Data autoconfig.
 */
// Task #11 — added `common.exception` to scanBasePackages so
// `GlobalExceptionHandler` @ControllerAdvice + DlmmException subclasses
// are picked up. Without this, throwing PoolNotFoundException /
// InvalidBinRangeException etc fell through to Spring Security's 403
// instead of the declared httpStatus (verified by G-22 PR #10 on the
// /preview-add-liquidity endpoint + the pre-existing /swap/quote).
// Same fix G-21 PR #8 applies to user-service.
/**
 * Spring Boot entry point for the DLMM pool-engine service (port 8083) — the
 * core DLMM domain: bins, liquidity, swaps, the volatility-driven variable
 * fee, scheduled liquidity ops, and margin watch.
 *
 * <p>The component-scan/entity/repository base packages are widened beyond
 * the local {@code com.sber.dlmm.pool} tree to pull in shared {@code dlmm-common}
 * infrastructure (transactional outbox, admin audit, and the exception
 * handler) — see the inline notes above each annotation for why each
 * extension is required. {@code @EnableScheduling} activates this service's
 * scheduled jobs (liquidity rollups, margin watch, limit-order watcher).
 */
@SpringBootApplication(scanBasePackages = {"com.sber.dlmm.pool", "com.sber.dlmm.common.exception"})
@EnableScheduling
@EntityScan(basePackages = {"com.sber.dlmm.pool", "com.sber.dlmm.common.outbox", "com.sber.dlmm.common.audit"})
@EnableJpaRepositories(basePackages = {"com.sber.dlmm.pool", "com.sber.dlmm.common.outbox", "com.sber.dlmm.common.audit"})
public class DlmmPoolEngineApplication {

    /**
     * Boots the Spring application context and starts the embedded server.
     *
     * @param args standard JVM command-line arguments, forwarded to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(DlmmPoolEngineApplication.class, args);
    }
}
