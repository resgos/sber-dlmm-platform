package com.sber.dlmm.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Sprint 9-DS-r4 (P2-13) — @EntityScan + @EnableJpaRepositories
 * extended to include {@code com.sber.dlmm.common.audit} so the
 * relocated {@code AdminAuditLog} + repository (moved out of
 * user-service into dlmm-common) are still picked up.
 */
@SpringBootApplication
@EntityScan(basePackages = {"com.sber.dlmm.user", "com.sber.dlmm.common.audit"})
@EnableJpaRepositories(basePackages = {"com.sber.dlmm.user", "com.sber.dlmm.common.audit"})
public class DlmmUserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DlmmUserServiceApplication.class, args);
    }
}
