package com.sber.dlmm.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Spring Boot entry point for <b>dlmm-user-service</b> (port 8081) — the
 * platform's identity and authentication authority. This service <b>issues</b>
 * the JWTs that every other module validates: HS384-signed access tokens
 * (~30&nbsp;min) and refresh tokens (7&nbsp;days), each carrying a {@code jti}
 * for Redis-backed revocation on logout. It also owns password hashing
 * (BCrypt), the KYC / platform-role model, organisation membership, 2FA and
 * 115-FZ self-restriction.
 *
 * <p>The custom scan configuration below is load-bearing — the inline notes
 * explain why the package set was widened beyond {@code com.sber.dlmm.user}.
 *
 * <p>Sprint 9-DS-r4 (P2-13) — @EntityScan + @EnableJpaRepositories
 * extended to include {@code com.sber.dlmm.common.audit} so the
 * relocated {@code AdminAuditLog} + repository (moved out of
 * user-service into dlmm-common) are still picked up.
 *
 * <p>Sprint 11 G-21 — {@code scanBasePackages} extended to pull in
 * {@link com.sber.dlmm.common.exception.GlobalExceptionHandler} so
 * {@link com.sber.dlmm.common.exception.DlmmException}s thrown from
 * {@code OrgService} translate to the documented HTTP status codes
 * (409 ORG_MEMBER_EXISTS etc.) rather than Spring Security's default
 * 403. (Pre-existing latent bug — without this scan, the only thing
 * that worked previously was the implicit conversion to 500 by
 * Spring's default error handler.)
 */
@SpringBootApplication(scanBasePackages = {"com.sber.dlmm.user", "com.sber.dlmm.common.exception"})
@EntityScan(basePackages = {"com.sber.dlmm.user", "com.sber.dlmm.common.audit"})
@EnableJpaRepositories(basePackages = {"com.sber.dlmm.user", "com.sber.dlmm.common.audit"})
public class DlmmUserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DlmmUserServiceApplication.class, args);
    }
}
