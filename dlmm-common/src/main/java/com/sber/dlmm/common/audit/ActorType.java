package com.sber.dlmm.common.audit;

/**
 * Sprint 13 G-28 / S13-02 — who initiated the audited action.
 *
 * <p>Before this enum, {@code admin_audit_log} silently assumed every
 * row came from an admin (KYC update, pool pause, OTC quote). Regulator
 * + 152-ФЗ multi-user attribution requires we also record user-side
 * mutations (swap, add/remove liquidity, fee claim) so the trail is
 * complete.
 *
 * <p>Used both on the {@link AdminAudit} annotation (defaults to
 * {@link #ADMIN} for backward compat) and persisted on
 * {@link AdminAuditLog#actorType} (default {@code 'ADMIN'} at the
 * column level so pre-existing rows behave correctly without backfill).
 */
public enum ActorType {
    /** Admin / SUPER_ADMIN action — original use-case. */
    ADMIN,
    /** End-user action (swap, add/remove liquidity, fee claim). */
    USER
}
