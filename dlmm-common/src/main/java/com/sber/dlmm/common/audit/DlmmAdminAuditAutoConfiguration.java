package com.sber.dlmm.common.audit;

import jakarta.persistence.EntityManagerFactory;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.security.core.Authentication;

/**
 * Sprint 9-DS-r4 (P2-13) — auto-config for the moved
 * {@code dlmm-common.audit} stack.
 *
 * <p>Activates when:
 *   <ul>
 *     <li>JPA is on the classpath ({@link EntityManagerFactory})</li>
 *     <li>AspectJ is on the classpath ({@link Aspect})</li>
 *     <li>The service hasn't explicitly disabled it via
 *         {@code dlmm.audit.enabled=false}</li>
 *   </ul>
 *
 * <p>Entity + repository discovery: each consuming service must extend
 * its {@code @EntityScan} and {@code @EnableJpaRepositories} on the
 * {@code @SpringBootApplication} class to include
 * {@code com.sber.dlmm.common.audit} alongside its own package. Same
 * pattern the outbox auto-config uses; documented in
 * {@code docs/DB-MIGRATION-CONVENTION.md} and re-stated in each
 * consuming service's {@code Application.java}.
 *
 * <p>{@link EnableAspectJAutoProxy} enables Spring AOP weaving for the
 * aspect — without it, the {@code @Around} advice never fires.
 */
/**
 * HOTFIX 2026-05-26 — added {@link Authentication} to the
 * {@code @ConditionalOnClass} guard. Before this fix, the aspect was
 * registered on services without spring-security (price-oracle,
 * notification-service), and Spring's AOP proxy creator tried to
 * reflect on the aspect's method signatures (which reference
 * {@code Authentication.class}), triggering {@code NoClassDefFoundError}
 * during startup → crashloop until R-01 restart policy gave up.
 *
 * The aspect's only purpose is to capture the authenticated user's
 * identity — without spring-security on the classpath there's nothing
 * to capture, so the entire audit chain self-skipping is the correct
 * behaviour for those services. Audit-enabled services (user-service,
 * pool-engine, fee-service, transaction-service, admin-bff, gateway)
 * all transitively pull spring-security via the dlmm-common JWT stack.
 */
@AutoConfiguration
@AutoConfigureAfter(JpaRepositoriesAutoConfiguration.class)
@ConditionalOnClass({EntityManagerFactory.class, Aspect.class, Authentication.class})
@ConditionalOnProperty(name = "dlmm.audit.enabled", havingValue = "true", matchIfMissing = true)
@EnableAspectJAutoProxy
public class DlmmAdminAuditAutoConfiguration {

    @Bean
    public AdminAuditService adminAuditService(AdminAuditLogRepository repository) {
        return new AdminAuditService(repository);
    }

    @Bean
    public AdminAuditAspect adminAuditAspect(AdminAuditService service) {
        return new AdminAuditAspect(service);
    }
}
