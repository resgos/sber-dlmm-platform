package com.sber.dlmm.token;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for {@code dlmm-token-service} (port 8082) — the token
 * catalog + balances service. It owns the token registry, per-user balances,
 * internal deduct/credit used by pool-engine during swaps/liquidity, the YSRUB
 * money-market, SberSpasibo loyalty, B2B issuer billing and custody fees.
 *
 * <p><b>Why the explicit scan lists below:</b> the service mixes its own beans
 * with infrastructure that physically lives in the {@code dlmm-common} library,
 * so the component/entity/repository scans are widened past the default
 * {@code com.sber.dlmm.token} root. Removing any of these packages silently
 * breaks wiring at boot (the failure modes are documented inline per annotation).
 *
 * <p><b>Amount-scale note (platform-wide #14):</b> token amounts handled by this
 * service are raw integers where 1 token = 10000 raw units (4 platform decimals);
 * prices, basis points and ratios are NOT scaled.
 *
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

    /**
     * Boots the Spring application context. Startup is gated by the shared
     * {@code SecretValidationOnStartup} bean (from dlmm-common), so the process
     * refuses to come up if {@code dlmm.jwt.secret} is missing or shorter than
     * 32 bytes.
     *
     * @param args standard JVM/Spring command-line arguments (e.g. {@code --server.port})
     */
    public static void main(String[] args) {
        SpringApplication.run(DlmmTokenServiceApplication.class, args);
    }
}
