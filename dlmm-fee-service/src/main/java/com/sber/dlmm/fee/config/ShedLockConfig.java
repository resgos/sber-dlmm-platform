package com.sber.dlmm.fee.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.ZoneOffset;

/**
 * Sprint 12 G-16 — ShedLock wiring for the auto-claim scheduler.
 *
 * <p>The {@code shedlock} table is created by Liquibase changeset
 * 002-create-auto-claim-tables. We configure the provider to use
 * UTC and the local {@code dlmm} schema (the default).
 *
 * <p>{@code defaultLockAtMostFor=PT5M} matches the value on
 * {@link com.sber.dlmm.fee.service.AutoClaimScheduler#tick()};
 * the per-method override there is for clarity, not correction.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .withTableName("shedlock")
                        .usingDbTime() // Postgres-side NOW() — no clock-skew foot-gun
                        .build()
        );
    }
}
