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
@AutoConfiguration
@AutoConfigureAfter(JpaRepositoriesAutoConfiguration.class)
@ConditionalOnClass({EntityManagerFactory.class, Aspect.class})
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
