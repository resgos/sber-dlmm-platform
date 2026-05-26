package com.sber.dlmm.common.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Sprint 8 #AU-4 — marks a controller method as an admin mutation that
 * must be captured by the audit log (audit C-6).
 *
 * <p>Sprint 13 G-28 / S13-02 — also covers user-side actions via the
 * {@link #actorType()} field (default {@link ActorType#ADMIN} for
 * backward compat). User-side call sites should prefer the
 * {@link UserAudit} alias annotation, which pre-sets
 * {@code actorType = USER}.
 *
 * <p>Lives in dlmm-common so any service that wires an {@code AdminAuditAspect}
 * + an audit-log writer can reuse the annotation. The aspect (see
 * {@link AdminAuditAspect}) reads the annotation, pulls the actor from
 * {@code SecurityContextHolder}, extracts target ID from a named method
 * parameter, executes the method, records SUCCESS or FAILED + exception
 * message.
 *
 * <p>Example (admin):
 * <pre>
 * &#64;PostMapping("/users/{id}/block")
 * &#64;PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
 * &#64;AdminAudit(action = "USER_BLOCK", targetType = "USER", targetIdParam = "id")
 * public ResponseEntity&lt;Void&gt; blockUser(&#64;PathVariable UUID id) { ... }
 * </pre>
 *
 * <p>Example (user-side):
 * <pre>
 * &#64;PostMapping("/swap")
 * &#64;UserAudit(action = "SWAP", targetType = "POOL")
 * public ResponseEntity&lt;SwapResponse&gt; swap(&#64;Valid &#64;RequestBody SwapRequest req) { ... }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminAudit {

    /**
     * Action label persisted to {@code admin_audit_log.action}. Convention:
     * UPPER_SNAKE noun_verb — {@code USER_BLOCK}, {@code POOL_PAUSE},
     * {@code TOKEN_LIST}, {@code SWAP}, {@code ADD_LIQUIDITY},
     * {@code CLAIM_FEES}. Used by analyst queries; keep stable across releases.
     */
    String action();

    /**
     * Target category. {@code USER}, {@code POOL}, {@code TOKEN}, {@code POSITION}
     * — coarse-grained filter axis. Empty string means "no specific target" (rare).
     */
    String targetType() default "";

    /**
     * Name of the annotated method's parameter whose {@code toString()} becomes
     * {@code target_id}. Path variables ({@code @PathVariable UUID id} → {@code "id"})
     * are the typical source. Empty means no target ID captured.
     */
    String targetIdParam() default "";

    /**
     * Sprint 13 G-28 / S13-02 — who is initiating the action. Defaults to
     * {@link ActorType#ADMIN} so every pre-existing call site (KYC update,
     * pool pause, OTC quote) behaves identically. End-user mutations
     * (swap, add/remove liquidity, fee claim) set this to
     * {@link ActorType#USER} — or use the {@link UserAudit} alias.
     */
    ActorType actorType() default ActorType.ADMIN;
}
