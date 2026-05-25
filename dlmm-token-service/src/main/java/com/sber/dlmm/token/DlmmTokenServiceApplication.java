package com.sber.dlmm.token;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @EntityScan and @EnableJpaRepositories list the local package PLUS
 * com.sber.dlmm.common.outbox. The latter is the shared OutboxEvent +
 * OutboxEventRepository extracted in Sprint 3 #3.9. Without these
 * extensions Spring Boot's default scan only finds entities/repos in
 * com.sber.dlmm.token and the shared outbox bean fails to wire.
 *
 * @EnableScheduling lights up the OutboxDispatcher tick + CustodyFeeJob.
 */
// Task #11 — common.exception scan so GlobalExceptionHandler picks up
// thrown DlmmException subclasses with their declared httpStatus
// instead of falling through to Spring Security 403. See pool-engine
// fix for the full story.
@SpringBootApplication(scanBasePackages = {"com.sber.dlmm.token", "com.sber.dlmm.common.exception"})
@EnableScheduling
// Sprint 9-DS-r4 (P2-13) — `com.sber.dlmm.common.audit` added too
// so the dlmm-common audit auto-config can find AdminAuditLog +
// repository if token-service ever opts in to the @AdminAudit aspect.
// Today it's a no-op (no AOP starter on classpath, auto-config gated
// off) but the scan covers a future opt-in without a second edit here.
@EntityScan(basePackages = {"com.sber.dlmm.token", "com.sber.dlmm.common.outbox", "com.sber.dlmm.common.audit"})
@EnableJpaRepositories(basePackages = {"com.sber.dlmm.token", "com.sber.dlmm.common.outbox", "com.sber.dlmm.common.audit"})
public class DlmmTokenServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DlmmTokenServiceApplication.class, args);
    }
}
