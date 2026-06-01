package com.sber.dlmm.user.repository;

import com.sber.dlmm.user.entity.OrgMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link OrgMember} rows (Sprint 11 G-21,
 * organisation membership + roles).
 *
 * <p>Supports the team-management flows: listing a roster, enforcing the
 * "last OWNER" invariant, resolving a user's active membership for the JWT
 * issuer, and de-duplicating invites. Inherits CRUD from
 * {@link JpaRepository}.
 */
@Repository
public interface OrgMemberRepository extends JpaRepository<OrgMember, UUID> {

    /**
     * Returns the full member roster of an org (ACTIVE and PENDING).
     *
     * @param orgId the organisation whose members to list
     * @return all membership rows for the org (unordered); empty if none
     */
    List<OrgMember> findByOrgId(UUID orgId);

    /** Members of the org with the given role — used to guard the
     *  "last OWNER" invariant on remove / changeRole.
     *
     * @param orgId the organisation to scope to
     * @param role  the role to filter by
     * @return membership rows in the org holding {@code role}; empty if none */
    List<OrgMember> findByOrgIdAndRole(UUID orgId, OrgMember.Role role);

    /** ACTIVE memberships for a given user. Used by the JWT issuer
     *  (login → embed {@code orgId}+{@code orgRole} claim) and by
     *  {@code GET /orgs/me}.
     *
     * @param userId the user whose memberships to load
     * @param status the membership status to filter by (typically
     *               {@link OrgMember.Status#ACTIVE})
     * @return matching membership rows; empty if the user is in no such org */
    List<OrgMember> findByUserIdAndStatus(UUID userId, OrgMember.Status status);

    /** Lookup for invite-deduplication. {@code orgId}+{@code email}
     *  pair must be unique across both ACTIVE and PENDING — preventing
     *  double-invite plus re-invite-of-existing-member.
     *
     * @param orgId the organisation to scope to
     * @param email the invitee email to match
     * @return the existing membership for this org+email, or empty if the
     *         email may be invited */
    Optional<OrgMember> findByOrgIdAndEmail(UUID orgId, String email);
}
