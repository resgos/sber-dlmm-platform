package com.sber.dlmm.common.exception;

/**
 * Sprint 11 G-21 — base for organisation-related errors thrown by
 * user-service's {@code OrgService}. Concrete subclasses pin specific
 * error codes so the frontend can switch on them; this base lets
 * non-domain consumers catch the whole family.
 */
public abstract class OrgException extends DlmmException {

    protected OrgException(String message, String errorCode, int httpStatus) {
        super(message, errorCode, httpStatus);
    }

    /** Org or member id not in DB. */
    public static class OrgNotFound extends OrgException {
        public OrgNotFound(String message) {
            super(message, "ORG_NOT_FOUND", 404);
        }
    }

    /** Caller is not a member of the org, or lacks the required role
     *  (e.g. inviting without OWNER). */
    public static class PermissionDenied extends OrgException {
        public PermissionDenied(String message) {
            super(message, "ORG_PERMISSION_DENIED", 403);
        }
    }

    /** Removal / role-change would leave the org without an OWNER. */
    public static class LastOwner extends OrgException {
        public LastOwner(String message) {
            super(message, "ORG_LAST_OWNER", 409);
        }
    }

    /** Email already invited or already a member of this org. */
    public static class MemberAlreadyExists extends OrgException {
        public MemberAlreadyExists(String message) {
            super(message, "ORG_MEMBER_EXISTS", 409);
        }
    }

    /** Calling user is already an ACTIVE member of an org and cannot
     *  create another (multi-org membership is on the Sprint 12
     *  roadmap). */
    public static class AlreadyHasOrg extends OrgException {
        public AlreadyHasOrg(String message) {
            super(message, "ORG_ALREADY_HAS_ORG", 409);
        }
    }

    /** Accept invite called by a user other than the invitee. */
    public static class InvalidInviteeIdentity extends OrgException {
        public InvalidInviteeIdentity(String message) {
            super(message, "ORG_INVALID_INVITEE", 403);
        }
    }

    /** Generic invalid-state guard (e.g. accept on ACTIVE row, demote
     *  non-OWNER as if they were one). */
    public static class InvalidState extends OrgException {
        public InvalidState(String message) {
            super(message, "ORG_INVALID_STATE", 400);
        }
    }
}
