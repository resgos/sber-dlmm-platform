package com.sber.dlmm.common.audit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Sprint 8 #AU-4, moved Sprint 9-DS-r4 (P2-13) — AOP around-advice
 * that captures every {@code @AdminAudit}-marked method call into
 * {@code admin_audit_log}.
 *
 * <p>Lives in {@code dlmm-common.audit} so any service (user, pool,
 * transaction, …) gets the same capture path. Registered by
 * {@link DlmmAdminAuditAutoConfiguration} when AOP + JPA are on the
 * classpath.
 *
 * <p>Captures actor from {@code SecurityContextHolder} (the userId
 * String placed by {@code JwtAuthenticationFilter}), target ID from
 * the named method parameter, action label from the annotation, and
 * SUCCESS/FAILED status from method outcome.
 *
 * <p>Audit-write uses {@link AdminAuditService#record} with
 * {@code REQUIRES_NEW} propagation — the audit row survives even
 * when the business transaction rolls back.
 *
 * <p>The aspect itself never throws — if anything in the capture
 * path blows up, we log and let the business call proceed/return
 * normally. Audit-write being broken must not break admin operations.
 */
@Aspect
public class AdminAuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditAspect.class);

    private final AdminAuditService auditService;

    public AdminAuditAspect(AdminAuditService auditService) {
        this.auditService = auditService;
    }

    @Around("@annotation(com.sber.dlmm.common.audit.AdminAudit)")
    public Object aroundAuditedMethod(ProceedingJoinPoint pjp) throws Throwable {
        AdminAudit annotation = extractAnnotation(pjp);
        AdminAuditLog.AdminAuditLogBuilder builder = startBuilder(pjp, annotation);

        try {
            Object result = pjp.proceed();
            builder.status(AdminAuditLog.Status.SUCCESS);
            safeRecord(builder.build());
            return result;
        } catch (Throwable ex) {
            builder.status(AdminAuditLog.Status.FAILED)
                    .errorMessage(truncate(ex.getClass().getSimpleName() + ": " + ex.getMessage(), 500));
            safeRecord(builder.build());
            throw ex;
        }
    }

    private AdminAudit extractAnnotation(ProceedingJoinPoint pjp) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        AdminAudit ann = method.getAnnotation(AdminAudit.class);
        if (ann == null) {
            throw new IllegalStateException("@AdminAudit pointcut matched a method without the annotation: "
                    + method);
        }
        return ann;
    }

    private AdminAuditLog.AdminAuditLogBuilder startBuilder(ProceedingJoinPoint pjp, AdminAudit ann) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        return AdminAuditLog.builder()
                .action(ann.action())
                .targetType(nullIfBlank(ann.targetType()))
                .targetId(extractTargetId(pjp, sig, ann.targetIdParam()))
                .actorUserId(parseActorUserId(auth))
                .actorRole(extractActorRole(auth))
                .methodSignature(sig.getDeclaringType().getSimpleName() + "." + sig.getName())
                .ipAddress(extractIpAddress());
    }

    private void safeRecord(AdminAuditLog row) {
        try {
            auditService.record(row);
        } catch (Exception ex) {
            log.warn("Audit-aspect record swallowed exception: {}", ex.getMessage());
        }
    }

    private static String extractTargetId(ProceedingJoinPoint pjp, MethodSignature sig, String paramName) {
        if (paramName == null || paramName.isBlank()) return null;
        String[] names = sig.getParameterNames();
        Object[] args = pjp.getArgs();
        if (names == null) return null;
        for (int i = 0; i < names.length; i++) {
            if (paramName.equals(names[i]) && args[i] != null) {
                return args[i].toString();
            }
        }
        return null;
    }

    private static UUID parseActorUserId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return null;
        try {
            return UUID.fromString(auth.getPrincipal().toString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String extractActorRole(Authentication auth) {
        if (auth == null || auth.getAuthorities() == null) return null;
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .findFirst()
                .orElse(null);
    }

    private static String extractIpAddress() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            jakarta.servlet.http.HttpServletRequest req = attrs.getRequest();
            String forwarded = req.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            }
            return req.getRemoteAddr();
        } catch (Exception ex) {
            return null;
        }
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
