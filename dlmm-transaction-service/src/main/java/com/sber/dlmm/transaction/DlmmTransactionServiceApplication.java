package com.sber.dlmm.transaction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Sprint 6 #6.9 — @EnableScheduling added for AmlScannerScheduler tick.
 * Also formalised @EntityScan + @EnableJpaRepositories so the shared
 * dlmm-common outbox infrastructure is discovered (previously relied on
 * scanBasePackages — works but less explicit).
 */
@SpringBootApplication(scanBasePackages = {"com.sber.dlmm.transaction", "com.sber.dlmm.common"})
@EnableScheduling
// Sprint 9-DS-r4 (P2-13) — `com.sber.dlmm.common.audit` added for
// the @AdminAudit aspect (shared admin_audit_log table). spring-boot-
// starter-aop in this service's pom activates DlmmAdminAuditAutoConfiguration.
@EntityScan(basePackages = {"com.sber.dlmm.transaction", "com.sber.dlmm.common.outbox", "com.sber.dlmm.common.audit"})
@EnableJpaRepositories(basePackages = {"com.sber.dlmm.transaction", "com.sber.dlmm.common.outbox", "com.sber.dlmm.common.audit"})
public class DlmmTransactionServiceApplication {

    /**
     * JVM entry point — boots the Spring context for transaction-service
     * (port 8085).
     *
     * @param args standard command-line arguments forwarded to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(DlmmTransactionServiceApplication.class, args);
    }
}
