package com.sber.dlmm.user.repository;

import com.sber.dlmm.user.entity.OrgMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrgMemberRepository extends JpaRepository<OrgMember, UUID> {

    List<OrgMember> findByOrgId(UUID orgId);

    /** Members of the org with the given role — used to guard the
     *  "last OWNER" invariant on remove / changeRole. */
    List<OrgMember> findByOrgIdAndRole(UUID orgId, OrgMember.Role role);

    /** ACTIVE memberships for a given user. Used by the JWT issuer
     *  (login → embed {@code orgId}+{@code orgRole} claim) and by
     *  {@code GET /orgs/me}. */
    List<OrgMember> findByUserIdAndStatus(UUID userId, OrgMember.Status status);

    /** Lookup for invite-deduplication. {@code orgId}+{@code email}
     *  pair must be unique across both ACTIVE and PENDING — preventing
     *  double-invite plus re-invite-of-existing-member. */
    Optional<OrgMember> findByOrgIdAndEmail(UUID orgId, String email);
}
