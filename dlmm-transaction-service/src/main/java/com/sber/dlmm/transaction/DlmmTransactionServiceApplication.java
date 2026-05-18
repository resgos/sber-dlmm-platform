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
@EntityScan(basePackages = {"com.sber.dlmm.transaction", "com.sber.dlmm.common.outbox"})
@EnableJpaRepositories(basePackages = {"com.sber.dlmm.transaction", "com.sber.dlmm.common.outbox"})
public class DlmmTransactionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DlmmTransactionServiceApplication.class, args);
    }
}
