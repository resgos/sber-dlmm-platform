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
 * <p>Sprint 13 G-28 / S13-02 — now also captures {@link UserAudit @UserAudit}
 * (pure alias of {@code @AdminAudit(actorType = USER)}). One aspect,
 * two annotations: avoids duplicate AOP wiring and lets the regulator
 * read both flavours from the same {@code admin_audit_log} table.
 *
 * <p>Lives in {@code dlmm-common.audit} so any service (user, pool,
 * transaction, fee …) gets the same capture path. Registered by
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

    /**
     * @param auditService the writer used to persist each captured audit row
     *                    (in its own {@code REQUIRES_NEW} transaction)
     */
    public AdminAuditAspect(AdminAuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * Around-advice for {@link AdminAudit @AdminAudit} methods. Reads the
     * annotation's {@code action}/{@code targetType}/{@code targetIdParam}/
     * {@code actorType} and routes through the shared {@link #capture} path.
     *
     * @param pjp the intercepted admin-mutation invocation
     * @return whatever the target method returns
     * @throws Throwable rethrown verbatim after a FAILED row is recorded, so the
     *         caller's error handling is unaffected
     */
    @Around("@annotation(com.sber.dlmm.common.audit.AdminAudit)")
    public Object aroundAdminAudited(ProceedingJoinPoint pjp) throws Throwable {
        AdminAudit annotation = requireAnnotation(pjp, AdminAudit.class);
        return capture(pjp, annotation.action(), annotation.targetType(),
                annotation.targetIdParam(), annotation.actorType());
    }

    /**
     * Sprint 13 G-28 / S13-02 — separate pointcut for {@link UserAudit}
     * so the aspect catches both annotations. AspectJ doesn't OR
     * pointcuts cleanly across two different annotation types when each
     * carries different fields, so two @Around methods (delegating to
     * one shared capture path) is the simpler shape.
     *
     * @param pjp the intercepted user-mutation invocation
     * @return whatever the target method returns
     * @throws Throwable rethrown verbatim after a FAILED row is recorded
     */
    @Around("@annotation(com.sber.dlmm.common.audit.UserAudit)")
    public Object aroundUserAudited(ProceedingJoinPoint pjp) throws Throwable {
        UserAudit annotation = requireAnnotation(pjp, UserAudit.class);
        return capture(pjp, annotation.action(), annotation.targetType(),
                annotation.targetIdParam(), ActorType.USER);
    }

    /**
     * Shared capture path — wraps the proceed() call with builder
     * setup + SUCCESS/FAILED record. Both annotations route through
     * here so we only have one place that ever talks to
     * {@link AdminAuditService}.
     *
     * <p>The target method always runs exactly once; a row is written for both
     * outcomes (SUCCESS, or FAILED with a truncated exception summary). The
     * original throwable is always rethrown so behavior is transparent to the
     * caller — only the audit side-effect is added.
     *
     * @param pjp the intercepted invocation
     * @param action the action label to persist
     * @param targetType the coarse target category (may be blank)
     * @param targetIdParam the parameter name whose value becomes the target id
     *                     (may be blank)
     * @param actorType ADMIN or USER, depending on which annotation matched
     * @return whatever the target method returns
     * @throws Throwable rethrown verbatim after the FAILED row is recorded
     */
    private Object capture(ProceedingJoinPoint pjp, String action, String targetType,
                            String targetIdParam, ActorType actorType) throws Throwable {
        AdminAuditLog.AdminAuditLogBuilder builder = startBuilder(
                pjp, action, targetType, targetIdParam, actorType);

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

    /**
     * Resolves the matched annotation instance off the intercepted method.
     *
     * @param pjp the intercepted invocation
     * @param type the annotation type the pointcut matched on
     * @param <A> the annotation type
     * @return the annotation present on the target method
     * @throws IllegalStateException if the pointcut matched a method that does
     *         not actually carry the annotation (should never happen)
     */
    private <A extends java.lang.annotation.Annotation> A requireAnnotation(ProceedingJoinPoint pjp, Class<A> type) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        A ann = method.getAnnotation(type);
        if (ann == null) {
            throw new IllegalStateException("@" + type.getSimpleName()
                    + " pointcut matched a method without the annotation: " + method);
        }
        return ann;
    }

    /**
     * Pre-populates an {@link AdminAuditLog} builder with everything known
     * before the method runs: action, actor type, target type/id, the actor's
     * userId and role (from {@code SecurityContextHolder}), the
     * {@code Class.method} signature and the client IP. The caller fills in the
     * SUCCESS/FAILED status afterward.
     *
     * @param pjp the intercepted invocation, source of the argument values
     * @param action the action label
     * @param targetType the coarse target category (blank ⇒ stored as null)
     * @param targetIdParam the parameter name carrying the target id (blank ⇒ none)
     * @param actorType ADMIN or USER
     * @return a partially built audit-log builder
     */
    private AdminAuditLog.AdminAuditLogBuilder startBuilder(ProceedingJoinPoint pjp,
                                                              String action,
                                                              String targetType,
                                                              String targetIdParam,
                                                              ActorType actorType) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        return AdminAuditLog.builder()
                .action(action)
                .actorType(actorType)
                .targetType(nullIfBlank(targetType))
                .targetId(extractTargetId(pjp, sig, targetIdParam))
                .actorUserId(parseActorUserId(auth))
                .actorRole(extractActorRole(auth))
                .methodSignature(sig.getDeclaringType().getSimpleName() + "." + sig.getName())
                .ipAddress(extractIpAddress());
    }

    /**
     * Persists the row, swallowing any failure. A broken audit write must never
     * propagate out of the aspect and break the business call (the writer itself
     * also guards this, making it defense-in-depth).
     *
     * @param row the fully built audit-log row to persist
     */
    private void safeRecord(AdminAuditLog row) {
        try {
            auditService.record(row);
        } catch (Exception ex) {
            log.warn("Audit-aspect record swallowed exception: {}", ex.getMessage());
        }
    }

    /**
     * Looks up the value of the named method parameter and returns its
     * {@code toString()} as the audit target id.
     *
     * @param pjp the intercepted invocation (source of argument values)
     * @param sig the method signature (source of parameter names)
     * @param paramName the parameter name to resolve; blank ⇒ no target id
     * @return the stringified argument value, or {@code null} if the parameter
     *         is absent, the value is null, or parameter names are unavailable
     */
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

    /**
     * Parses the authenticated principal into a userId UUID. The inbound JWT
     * filter stores the userId as the principal, so this is the acting user.
     *
     * @param auth the current authentication (may be null/anonymous)
     * @return the actor's userId, or {@code null} if unauthenticated or the
     *         principal isn't a UUID
     */
    private static UUID parseActorUserId(Authentication auth) {
        if (auth == null || auth.getPrincipal() == null) return null;
        try {
            return UUID.fromString(auth.getPrincipal().toString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Picks the first {@code ROLE_*} authority as the actor's role, for
     * privilege-escalation analysis alongside the actor type.
     *
     * @param auth the current authentication (may be null)
     * @return the first {@code ROLE_*} authority, or {@code null} if none
     */
    private static String extractActorRole(Authentication auth) {
        if (auth == null || auth.getAuthorities() == null) return null;
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .findFirst()
                .orElse(null);
    }

    /**
     * Resolves the caller's IP from the current servlet request. Prefers the
     * first hop of {@code X-Forwarded-For} (the real client when behind the
     * gateway) and falls back to the socket {@code RemoteAddr}.
     *
     * @return the client IP, or {@code null} when there is no request scope
     */
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

    /**
     * Normalizes blank input to {@code null} so empty annotation defaults are
     * stored as SQL NULL rather than the empty string.
     *
     * @param s the value to normalize
     * @return {@code s}, or {@code null} if it is null or blank
     */
    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Caps a string at {@code max} characters so it fits the bounded
     * {@code error_message} column.
     *
     * @param s the value to truncate (may be null)
     * @param max the maximum length to keep
     * @return {@code s} unchanged if within {@code max}; otherwise its first
     *         {@code max} characters; {@code null} if {@code s} is null
     */
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
