package com.sber.dlmm.common.exception;

/**
 * Sprint 6 #6.7 — thrown when a user with active 115-ФЗ самозапрет
 * attempts to open a new position (swap / hedge / add-liquidity).
 * Caller sees HTTP 403 with code USER_SELF_RESTRICTED.
 *
 * <p>Existing positions remain operable — only NEW-money-out paths
 * are blocked. Lift requires a 7-day cooling period via the user-service
 * self-restriction API (and Sprint 7+ real ЦБ РФ verification step).
 */
public class UserSelfRestrictedException extends DlmmException {
    /**
     * @param message human-readable detail explaining the active self-restriction (surfaced in the error body)
     */
    public UserSelfRestrictedException(String message) {
        super(message, "USER_SELF_RESTRICTED", 403);
    }
}
