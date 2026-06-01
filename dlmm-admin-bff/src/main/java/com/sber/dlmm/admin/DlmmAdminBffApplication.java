package com.sber.dlmm.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the admin backend-for-frontend (BFF, port 8088).
 *
 * <p>This service is not a domain owner: it holds no business tables of its
 * own and exists purely to aggregate and proxy the admin-facing slices of the
 * downstream domain services (user / token / pool-engine / fee / transaction /
 * price-oracle) into the shapes the admin React SPA needs. Every outbound call
 * goes through a Resilience4j circuit-breaker + retry wrapper so a single slow
 * or down downstream degrades to a canned empty body (200) rather than hanging
 * the whole admin UI.
 *
 * <p>The {@code scanBasePackages} deliberately includes
 * {@code com.sber.dlmm.common.exception} in addition to the local package: that
 * pulls the shared {@code GlobalExceptionHandler} into this context so thrown
 * {@code DlmmException} subclasses render with their declared {@code httpStatus}
 * instead of being swallowed by Spring Security and surfacing as a generic 403.
 * (See the pool-engine fix referenced below for the full diagnosis.)
 */
// Task #11 — common.exception scan so GlobalExceptionHandler picks up
// thrown DlmmException subclasses with their declared httpStatus
// instead of falling through to Spring Security 403. See pool-engine
// fix for the full story.
@SpringBootApplication(scanBasePackages = {"com.sber.dlmm.admin", "com.sber.dlmm.common.exception"})
public class DlmmAdminBffApplication {

    /**
     * JVM entry point — boots the Spring application context for the admin BFF.
     *
     * @param args standard command-line arguments, forwarded verbatim to
     *             {@link SpringApplication#run(Class, String...)} (Spring/Boot
     *             property overrides, profile flags, etc.)
     */
    public static void main(String[] args) {
        SpringApplication.run(DlmmAdminBffApplication.class, args);
    }
}
