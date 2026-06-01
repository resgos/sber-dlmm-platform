package com.sber.dlmm.common.exception;

/**
 * Sprint 11 G-21 — base for organisation-related errors thrown by
 * user-service's {@code OrgService}. Concrete subclasses pin specific
 * error codes so the frontend can switch on them; this base lets
 * non-domain consumers catch the whole family.
 */
public abstract class OrgException extends DlmmException {

    /**
     * Subclass-only constructor that pins the concrete error code and HTTP status for each
     * organisation error variant.
     *
     * @param message    human-readable detail surfaced in the error body
     * @param errorCode  stable {@code ORG_*} code the frontend switches on (e.g. {@code "ORG_NOT_FOUND"})
     * @param httpStatus HTTP status the client receives (404 / 403 / 409 / 400 depending on the variant)
     */
    protected OrgException(String message, String errorCode, int httpStatus) {
        super(message, errorCode, httpStatus);
    }

    /**
     * Thrown when an org or member id is not in the DB. Maps to <b>HTTP 404</b> with code
     * {@code "ORG_NOT_FOUND"}.
     */
    public static class OrgNotFound extends OrgException {
        /**
         * @param message human-readable detail (typically the missing org/member id) surfaced in the error body
         */
        public OrgNotFound(String message) {
            super(message, "ORG_NOT_FOUND", 404);
        }
    }

    /**
     * Thrown when the caller is not a member of the org, or lacks the required role
     * (e.g. inviting without OWNER). Maps to <b>HTTP 403</b> with code {@code "ORG_PERMISSION_DENIED"}.
     */
    public static class PermissionDenied extends OrgException {
        /**
         * @param message human-readable detail about the missing membership/role (surfaced in the error body)
         */
        public PermissionDenied(String message) {
            super(message, "ORG_PERMISSION_DENIED", 403);
        }
    }

    /**
     * Thrown when a removal / role-change would leave the org without an OWNER. Maps to
     * <b>HTTP 409 Conflict</b> with code {@code "ORG_LAST_OWNER"}.
     */
    public static class LastOwner extends OrgException {
        /**
         * @param message human-readable detail about the last-owner constraint (surfaced in the error body)
         */
        public LastOwner(String message) {
            super(message, "ORG_LAST_OWNER", 409);
        }
    }

    /**
     * Thrown when the email is already invited or already a member of this org. Maps to
     * <b>HTTP 409 Conflict</b> with code {@code "ORG_MEMBER_EXISTS"}.
     */
    public static class MemberAlreadyExists extends OrgException {
        /**
         * @param message human-readable detail (typically the conflicting email) surfaced in the error body
         */
        public MemberAlreadyExists(String message) {
            super(message, "ORG_MEMBER_EXISTS", 409);
        }
    }

    /**
     * Thrown when the calling user is already an ACTIVE member of an org and cannot create another
     * (multi-org membership is on the Sprint 12 roadmap). Maps to <b>HTTP 409 Conflict</b> with code
     * {@code "ORG_ALREADY_HAS_ORG"}.
     */
    public static class AlreadyHasOrg extends OrgException {
        /**
         * @param message human-readable detail about the existing-membership conflict (surfaced in the error body)
         */
        public AlreadyHasOrg(String message) {
            super(message, "ORG_ALREADY_HAS_ORG", 409);
        }
    }

    /**
     * Thrown when accept-invite is called by a user other than the invitee. Maps to <b>HTTP 403</b>
     * with code {@code "ORG_INVALID_INVITEE"}.
     */
    public static class InvalidInviteeIdentity extends OrgException {
        /**
         * @param message human-readable detail about the invitee-identity mismatch (surfaced in the error body)
         */
        public InvalidInviteeIdentity(String message) {
            super(message, "ORG_INVALID_INVITEE", 403);
        }
    }

    /**
     * Generic invalid-state guard (e.g. accept on an ACTIVE row, demote a non-OWNER as if they were
     * one). Maps to <b>HTTP 400</b> with code {@code "ORG_INVALID_STATE"}.
     */
    public static class InvalidState extends OrgException {
        /**
         * @param message human-readable detail about the illegal state transition (surfaced in the error body)
         */
        public InvalidState(String message) {
            super(message, "ORG_INVALID_STATE", 400);
        }
    }
}
