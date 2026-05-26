package com.sber.dlmm.common.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Sprint 13 G-28 / S13-02 — marks a controller method as a user-side
 * mutation (swap, add/remove liquidity, fee claim, hedge open/close)
 * that must be captured in {@code admin_audit_log} alongside the
 * existing {@link AdminAudit ADMIN actions}.
 *
 * <p>Pure alias: the {@link AdminAuditAspect} treats this exactly like
 * {@code @AdminAudit(actorType = USER, ...)} so we avoid duplicating
 * the AOP wiring. Reading {@code @UserAudit} at call sites is clearer
 * intent than {@code @AdminAudit(actorType = USER)} — regulator and
 * code-reviewer can scan for "USER-side audit" without parsing fields.
 *
 * <p>Example:
 * <pre>
 * &#64;PostMapping("/swap")
 * &#64;UserAudit(action = "SWAP", targetType = "POOL", targetIdParam = "poolId")
 * public ResponseEntity&lt;SwapResponse&gt; swap(&#64;Valid &#64;RequestBody SwapRequest req) { ... }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface UserAudit {

    /**
     * Action label persisted to {@code admin_audit_log.action}. Convention:
     * UPPER_SNAKE — {@code SWAP}, {@code ADD_LIQUIDITY},
     * {@code REMOVE_LIQUIDITY}, {@code CLAIM_FEES}, {@code HEDGE_OPEN},
     * {@code HEDGE_CLOSE}. Used by analyst queries; keep stable across releases.
     */
    String action();

    /**
     * Target category. {@code POOL}, {@code POSITION}, {@code TOKEN} —
     * coarse-grained filter axis. Empty string means "no specific target".
     */
    String targetType() default "";

    /**
     * Name of the annotated method's parameter whose {@code toString()} becomes
     * {@code target_id}. Path variables and request-body record components are
     * both supported (the aspect introspects the parameter list by name).
     * Empty means no target ID captured.
     */
    String targetIdParam() default "";
}
