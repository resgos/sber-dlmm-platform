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
@SpringBootApplication
@EnableScheduling
@EntityScan(basePackages = {"com.sber.dlmm.token", "com.sber.dlmm.common.outbox"})
@EnableJpaRepositories(basePackages = {"com.sber.dlmm.token", "com.sber.dlmm.common.outbox"})
public class DlmmTokenServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DlmmTokenServiceApplication.class, args);
    }
}
