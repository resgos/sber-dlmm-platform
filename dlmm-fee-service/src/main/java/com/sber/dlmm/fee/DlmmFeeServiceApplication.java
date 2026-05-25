package com.sber.dlmm.fee;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Sprint 12 G-16:
 *   - {@code @EnableScheduling} added so
 *     {@link com.sber.dlmm.fee.service.AutoClaimScheduler#tick()} fires.
 *     ShedLock is wired separately in
 *     {@link com.sber.dlmm.fee.config.ShedLockConfig}.
 *   - {@code @EntityScan} / {@code @EnableJpaRepositories} extended to
 *     include {@code com.sber.dlmm.common.audit} — the
 *     {@code DlmmAdminAuditAutoConfiguration} is no-op-gated on
 *     {@code Aspect.class} being on classpath, which fee-service's
 *     {@code spring-boot-starter-aop} (used by Resilience4j) brings in.
 *     Without the scan extension the auto-config's
 *     {@code AdminAuditService} bean fails to wire its repository.
 *     Mirrors the same setup in {@code DlmmTokenServiceApplication}.
 *
 * Task #11 — scanBasePackages extended to include
 * {@code com.sber.dlmm.common.exception} so GlobalExceptionHandler
 * @ControllerAdvice picks up declared httpStatus on DlmmException
 * subclasses (otherwise they fall through to Spring Security 403).
 */
@SpringBootApplication(scanBasePackages = {"com.sber.dlmm.fee", "com.sber.dlmm.common.exception"})
@EnableScheduling
@EntityScan(basePackages = {"com.sber.dlmm.fee", "com.sber.dlmm.common.audit"})
@EnableJpaRepositories(basePackages = {"com.sber.dlmm.fee", "com.sber.dlmm.common.audit"})
public class DlmmFeeServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DlmmFeeServiceApplication.class, args);
    }
}
