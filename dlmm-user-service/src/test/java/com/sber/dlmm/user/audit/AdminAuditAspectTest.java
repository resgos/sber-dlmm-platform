package com.sber.dlmm.user.audit;

import com.sber.dlmm.common.audit.AdminAudit;
import com.sber.dlmm.user.entity.AdminAuditLog;
import com.sber.dlmm.user.service.AdminAuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Sprint 8 AU-4 — verify the aspect captures the right fields on both
 * SUCCESS and FAILED paths.
 *
 * <p>Uses Spring's {@link AspectJProxyFactory} to wire the aspect around
 * a plain Java target — no Spring context boot, no JPA. The aspect must
 * work standalone so this test pins the contract independent of the
 * surrounding application.
 */
class AdminAuditAspectTest {

    private AdminAuditService service;
    private AuditedTarget proxied;

    @BeforeEach
    void setUp() {
        service = mock(AdminAuditService.class);
        AspectJProxyFactory factory = new AspectJProxyFactory(new AuditedTarget());
        factory.addAspect(new AdminAuditAspect(service));
        proxied = factory.getProxy();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void successPath_capturesAllFields() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        seedActor(actorId, "ROLE_ADMIN");

        proxied.blockUser(targetId);

        AdminAuditLog row = capture();
        assertThat(row.getAction()).isEqualTo("USER_BLOCK");
        assertThat(row.getTargetType()).isEqualTo("USER");
        assertThat(row.getTargetId()).isEqualTo(targetId.toString());
        assertThat(row.getActorUserId()).isEqualTo(actorId);
        assertThat(row.getActorRole()).isEqualTo("ROLE_ADMIN");
        assertThat(row.getStatus()).isEqualTo(AdminAuditLog.Status.SUCCESS);
        assertThat(row.getErrorMessage()).isNull();
        assertThat(row.getMethodSignature()).isEqualTo("AuditedTarget.blockUser");
    }

    @Test
    void failedPath_capturesExceptionMessage_andRethrows() {
        UUID actorId = UUID.randomUUID();
        seedActor(actorId, "ROLE_SUPER_ADMIN");

        assertThatThrownBy(() -> proxied.explodingMethod(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kaboom");

        AdminAuditLog row = capture();
        assertThat(row.getAction()).isEqualTo("EXPLODE");
        assertThat(row.getStatus()).isEqualTo(AdminAuditLog.Status.FAILED);
        assertThat(row.getErrorMessage())
                .contains("IllegalStateException")
                .contains("kaboom");
        assertThat(row.getActorRole()).isEqualTo("ROLE_SUPER_ADMIN");
    }

    @Test
    void anonymousActor_capturesNullActorFields() {
        // No SecurityContext authentication — should still record the audit
        // (rare but possible if a misconfig lets an admin endpoint through).
        UUID targetId = UUID.randomUUID();
        proxied.blockUser(targetId);

        AdminAuditLog row = capture();
        assertThat(row.getAction()).isEqualTo("USER_BLOCK");
        assertThat(row.getTargetId()).isEqualTo(targetId.toString());
        assertThat(row.getActorUserId()).isNull();
        assertThat(row.getActorRole()).isNull();
        assertThat(row.getStatus()).isEqualTo(AdminAuditLog.Status.SUCCESS);
    }

    @Test
    void noTargetIdParam_capturesNullTargetId() {
        seedActor(UUID.randomUUID(), "ROLE_ADMIN");

        proxied.actionWithoutTarget();

        AdminAuditLog row = capture();
        assertThat(row.getAction()).isEqualTo("NO_TARGET_ACTION");
        assertThat(row.getTargetId()).isNull();
        assertThat(row.getTargetType()).isNull();
    }

    @Test
    void unannotatedMethod_doesNotTriggerAudit() {
        seedActor(UUID.randomUUID(), "ROLE_ADMIN");

        proxied.notAudited();

        verify(service, org.mockito.Mockito.never()).record(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void auditServiceFailure_doesNotPropagate() {
        // Audit-write failures must not break the business path.
        seedActor(UUID.randomUUID(), "ROLE_ADMIN");
        org.mockito.Mockito.doThrow(new RuntimeException("audit store down"))
                .when(service).record(org.mockito.ArgumentMatchers.any());

        // Business call still succeeds (returns normally).
        proxied.blockUser(UUID.randomUUID());
        // No exception thrown — aspect swallowed it.
    }

    @Test
    void exceptionMessageTruncatedTo500() {
        seedActor(UUID.randomUUID(), "ROLE_ADMIN");
        String longMessage = "x".repeat(700);

        assertThatThrownBy(() -> proxied.throwLong(UUID.randomUUID(), longMessage))
                .isInstanceOf(IllegalArgumentException.class);

        AdminAuditLog row = capture();
        assertThat(row.getErrorMessage()).hasSize(500);
        assertThat(row.getErrorMessage()).startsWith("IllegalArgumentException:");
    }

    // ── helpers ──

    private void seedActor(UUID actorId, String role) {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(
                        actorId.toString(),
                        "VERIFIED",
                        java.util.List.of(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private AdminAuditLog capture() {
        ArgumentCaptor<AdminAuditLog> cap = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(service).record(cap.capture());
        return cap.getValue();
    }

    /**
     * Plain class — must be public so Spring's AspectJProxyFactory can
     * generate a CGLIB subclass. Methods exercise each branch of the
     * aspect (success, failure, no-target, unannotated).
     */
    public static class AuditedTarget {

        @AdminAudit(action = "USER_BLOCK", targetType = "USER", targetIdParam = "id")
        public void blockUser(UUID id) {
            // success path
        }

        @AdminAudit(action = "EXPLODE", targetType = "USER", targetIdParam = "id")
        public void explodingMethod(UUID id) {
            throw new IllegalStateException("kaboom");
        }

        @AdminAudit(action = "NO_TARGET_ACTION")
        public void actionWithoutTarget() {
            // success path, no target
        }

        @AdminAudit(action = "THROW_LONG", targetType = "USER", targetIdParam = "id")
        public void throwLong(UUID id, String msg) {
            throw new IllegalArgumentException(msg);
        }

        // No @AdminAudit — must not be captured.
        public void notAudited() {
            // no-op
        }
    }
}
