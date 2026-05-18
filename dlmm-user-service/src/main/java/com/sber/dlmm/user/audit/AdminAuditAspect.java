package com.sber.dlmm.user.audit;

import com.sber.dlmm.common.audit.AdminAudit;
import com.sber.dlmm.user.entity.AdminAuditLog;
import com.sber.dlmm.user.service.AdminAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Sprint 8 #AU-4 — AOP around-advice that captures every
 * {@code @AdminAudit}-marked method call into {@code admin_audit_log}.
 *
 * <p>Captures actor from {@code SecurityContextHolder} (the userId String
 * placed by {@code JwtAuthenticationFilter}), target ID from the named
 * method parameter ({@link AdminAudit#targetIdParam()}), action label
 * from the annotation, and SUCCESS/FAILED status from method outcome.
 *
 * <p>Audit-write happens via {@link AdminAuditService#record(AdminAuditLog)}
 * which uses {@code REQUIRES_NEW} propagation — so the audit row survives
 * even when the business transaction rolls back.
 *
 * <p>The aspect itself never throws — if anything in the capture path
 * blows up, we log and let the business call proceed/return normally.
 * Audit-write being broken must not break admin operations.
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminAuditAspect {

    private final AdminAuditService auditService;

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
            // Shouldn't happen — pointcut filters on the annotation — but
            // defensive in case of weaving oddities (e.g. interface vs impl).
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
            // AdminAuditService.record() already catches & logs, but in case
            // a wrapping proxy throws we still must not propagate.
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
            // X-Forwarded-For is set by the gateway; first IP is the original client.
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
