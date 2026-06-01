package com.sber.dlmm.common.enums;

/**
 * Authorization role assigned to a user account.
 *
 * <p>Carried in the JWT {@code role} claim, propagated downstream via the
 * gateway's {@code X-User-Role} header, and enforced by Spring Security
 * method/URL rules in each service. Roles are not strictly hierarchical in
 * code — authorization checks name the specific roles they require.
 */
public enum UserRole {
    /** Standard retail customer — trade and basic account operations. */
    USER,
    /** User who provides pool liquidity; may unlock LP-specific features. */
    LIQUIDITY_PROVIDER,
    /** Back-office operator — access to the admin UI / admin endpoints. */
    ADMIN,
    /** Highest-privilege administrator — sensitive / destructive operations
     *  (e.g. emergency controls) beyond a regular {@link #ADMIN}. */
    SUPER_ADMIN
}
