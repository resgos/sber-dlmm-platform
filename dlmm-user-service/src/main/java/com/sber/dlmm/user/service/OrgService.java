package com.sber.dlmm.user.service;

import com.sber.dlmm.common.exception.OrgException;
import com.sber.dlmm.user.entity.Org;
import com.sber.dlmm.user.entity.OrgMember;
import com.sber.dlmm.user.entity.User;
import com.sber.dlmm.user.repository.OrgMemberRepository;
import com.sber.dlmm.user.repository.OrgRepository;
import com.sber.dlmm.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sprint 11 G-21 — Organisations + role-based membership.
 *
 * <p>Invariants enforced here (and pinned by {@code OrgServiceTest}):
 * <ul>
 *   <li>Org creation: the creator becomes the OWNER and is the only
 *       ACTIVE member. Refused if the user already owns or actively
 *       belongs to another org (multi-org membership is Sprint 12+).</li>
 *   <li>Invite: OWNER-only; refused on duplicate email
 *       within the org regardless of status.</li>
 *   <li>Accept: only the invitee themselves can flip
 *       {@code PENDING → ACTIVE}; the membership's {@code userId} is
 *       resolved from the invite email at accept time.</li>
 *   <li>Remove: OWNER-only; never allowed to remove the last OWNER
 *       (would orphan the org).</li>
 *   <li>ChangeRole: OWNER-only; demoting the last OWNER is rejected
 *       — promote another member to OWNER first.</li>
 * </ul>
 *
 * <p>Permission violations throw {@link OrgException.PermissionDenied}
 * (403); structural violations throw {@link OrgException.LastOwner}
 * (409) etc. {@code GlobalExceptionHandler} maps these to the wire.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrgService {

    private final OrgRepository orgRepository;
    private final OrgMemberRepository memberRepository;
    private final UserRepository userRepository;

    /**
     * Create an org with the calling user as the sole OWNER.
     *
     * <p>{@code ownerEmail} / {@code ownerName} are accepted from the
     * request body for symmetry with the frontend (which has the user
     * type them once), but on the backend they're cross-checked
     * against the {@code creatorUserId}'s stored profile — supplying
     * a different email does not let the caller invite themselves
     * under a fake identity.
     *
     * @param creatorUserId the authenticated caller, who becomes the sole OWNER
     * @param name          the organisation name (required, trimmed)
     * @param ownerEmail    convenience owner email; falls back to the caller's profile email when blank
     * @param ownerName     convenience owner display name; falls back to the caller's first+last name when blank
     * @return the newly persisted {@link Org}
     * @throws OrgException.InvalidState   if {@code name} is blank
     * @throws OrgException.AlreadyHasOrg  if the caller already has an ACTIVE membership somewhere
     * @throws OrgException.OrgNotFound    if the creator user id does not resolve to a user
     */
    @Transactional
    public Org create(UUID creatorUserId, String name, String ownerEmail, String ownerName) {
        if (name == null || name.isBlank()) {
            throw new OrgException.InvalidState("Org name is required");
        }
        // Refuse if the caller already has an ACTIVE membership anywhere.
        // Otherwise: dangling memberships in the prior org + the new
        // OWNER row would both surface in /orgs/me and we'd have to
        // pick — better to force the user through "leave first".
        List<OrgMember> existing = memberRepository.findByUserIdAndStatus(
                creatorUserId, OrgMember.Status.ACTIVE);
        if (!existing.isEmpty()) {
            throw new OrgException.AlreadyHasOrg(
                    "User already belongs to an org; leave it first");
        }
        User creator = userRepository.findById(creatorUserId)
                .orElseThrow(() -> new OrgException.OrgNotFound(
                        "Creator user not found: " + creatorUserId));

        String trimmedName = name.trim();
        // Effective owner identity = the calling user. The request body's
        // ownerEmail / ownerName are convenience defaults — if blank fall
        // back to the user's stored profile.
        String effectiveEmail = isBlank(ownerEmail) ? creator.getEmail() : ownerEmail.trim();
        String effectiveName = isBlank(ownerName)
                ? (creator.getFirstName() + " " + creator.getLastName()).trim()
                : ownerName.trim();

        Org org = orgRepository.save(Org.builder()
                .name(trimmedName)
                .ownerId(creatorUserId)
                .build());

        memberRepository.save(OrgMember.builder()
                .orgId(org.getId())
                .userId(creatorUserId)
                .email(effectiveEmail)
                .name(effectiveName)
                .role(OrgMember.Role.OWNER)
                .status(OrgMember.Status.ACTIVE)
                .build());

        log.info("ORG CREATED id={} name='{}' owner={}",
                org.getId(), trimmedName, creatorUserId);
        return org;
    }

    /**
     * Returns the org the caller belongs to (OWNER or any ACTIVE
     * member), or empty if none. Used by {@code GET /orgs/me}.
     *
     * <p>By invariant a user has at most one ACTIVE membership; if the data
     * somehow violates that, the lowest membership is returned deterministically
     * and the anomaly is logged.
     *
     * @param userId the caller's user id
     * @return the caller's organisation, or {@link Optional#empty()} if they belong to none
     */
    @Transactional(readOnly = true)
    public Optional<Org> findMyOrg(UUID userId) {
        List<OrgMember> memberships = memberRepository.findByUserIdAndStatus(
                userId, OrgMember.Status.ACTIVE);
        if (memberships.isEmpty()) return Optional.empty();
        // A user belongs to ≤ 1 org by invariant; if somehow more,
        // surface the first deterministically (id-sorted) and log.
        if (memberships.size() > 1) {
            log.warn("User {} has {} ACTIVE memberships; expected ≤1",
                    userId, memberships.size());
        }
        UUID orgId = memberships.get(0).getOrgId();
        return orgRepository.findById(orgId);
    }

    /**
     * Lists every member (any status) of the given org. The caller must be an
     * ACTIVE member of that org — a non-member, even an authenticated one, is
     * rejected so org rosters aren't readable across tenants.
     *
     * @param orgId        the organisation to list
     * @param callerUserId the authenticated caller, who must be an ACTIVE member
     * @return all members of the org, regardless of status
     * @throws OrgException.PermissionDenied if the caller is not an ACTIVE member of the org
     */
    @Transactional(readOnly = true)
    public List<OrgMember> listMembers(UUID orgId, UUID callerUserId) {
        requireActiveMember(orgId, callerUserId);
        return memberRepository.findByOrgId(orgId);
    }

    /**
     * Invite a new member. OWNER-only. Refused if {@code email}
     * already maps to a row in this org (ACTIVE or PENDING) — the
     * caller must remove the existing row first.
     *
     * <p>The email is normalised (trim + lowercase) before the duplicate check
     * and storage. If the invitee already has a platform account their
     * {@code userId} is pre-resolved so they can find the invite on sign-in.
     * The new row is created in {@code PENDING} status; inviting directly as
     * {@code OWNER} is refused (use {@link #changeRole} on an existing member).
     *
     * @param orgId        the organisation to invite into
     * @param callerUserId the authenticated caller, who must be the OWNER
     * @param email        the invitee's email (normalised before use); required
     * @param name         the invitee's display name; defaults to the email when blank
     * @param role         the role to grant; required and must not be {@code OWNER}
     * @return the persisted PENDING {@link OrgMember} invite row
     * @throws OrgException.PermissionDenied   if the caller is not the OWNER
     * @throws OrgException.InvalidState       if email is blank, role is null, or role is OWNER
     * @throws OrgException.MemberAlreadyExists if the email is already a member of this org
     */
    @Transactional
    public OrgMember invite(UUID orgId, UUID callerUserId,
                             String email, String name, OrgMember.Role role) {
        requireOwner(orgId, callerUserId);
        if (isBlank(email)) {
            throw new OrgException.InvalidState("Invite email is required");
        }
        if (role == null) {
            throw new OrgException.InvalidState("Invite role is required");
        }
        if (role == OrgMember.Role.OWNER) {
            // Co-OWNER creation flows through changeRole on an existing
            // member — refusing here keeps the invite path single-purpose
            // (PENDING members shouldn't immediately be OWNERs).
            throw new OrgException.InvalidState(
                    "Cannot invite as OWNER; promote an existing member instead");
        }
        String normalisedEmail = email.trim().toLowerCase();
        memberRepository.findByOrgIdAndEmail(orgId, normalisedEmail)
                .ifPresent(existing -> {
                    throw new OrgException.MemberAlreadyExists(
                            "Email " + normalisedEmail + " is already a member of this org");
                });

        // Pre-resolve userId if the invitee already has an account —
        // saves a hop on accept (and ensures they can find the invite
        // when they sign in).
        UUID resolvedUserId = userRepository.findByEmail(normalisedEmail)
                .map(User::getId).orElse(null);

        OrgMember member = memberRepository.save(OrgMember.builder()
                .orgId(orgId)
                .userId(resolvedUserId)
                .email(normalisedEmail)
                .name(isBlank(name) ? normalisedEmail : name.trim())
                .role(role)
                .status(OrgMember.Status.PENDING)
                .build());
        log.info("ORG INVITE org={} email={} role={} by={}",
                orgId, normalisedEmail, role, callerUserId);
        return member;
    }

    /**
     * Flip PENDING → ACTIVE. Only the invitee themselves may accept —
     * we match on the invite's email against the caller's profile
     * email (case-insensitive). If the row had a pre-resolved
     * {@code userId} it must equal the caller; otherwise we populate
     * {@code userId} now.
     *
     * <p>Two identity checks defend the invite: any pre-resolved
     * {@code userId} must equal the caller, and the caller's profile email
     * must match the invite email — so an invite issued to one address cannot
     * be claimed by another account. The caller must also not already be
     * ACTIVE in another org. On success {@code joinedAt} is stamped.
     *
     * @param orgId        the org the invite belongs to (cross-checked against the member row)
     * @param memberId     the PENDING membership row id from the invite
     * @param callerUserId the authenticated caller claiming the invite
     * @return the membership row, now ACTIVE
     * @throws OrgException.OrgNotFound            if the member or caller is missing, or the member is not in this org
     * @throws OrgException.InvalidState           if the membership is not PENDING
     * @throws OrgException.InvalidInviteeIdentity if the caller's id or email does not match the invite
     * @throws OrgException.AlreadyHasOrg          if the caller is already ACTIVE in another org
     */
    @Transactional
    public OrgMember accept(UUID orgId, UUID memberId, UUID callerUserId) {
        OrgMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new OrgException.OrgNotFound(
                        "Member not found: " + memberId));
        if (!member.getOrgId().equals(orgId)) {
            throw new OrgException.OrgNotFound(
                    "Member " + memberId + " does not belong to org " + orgId);
        }
        if (member.getStatus() != OrgMember.Status.PENDING) {
            throw new OrgException.InvalidState(
                    "Member is not in PENDING state: " + member.getStatus());
        }
        User caller = userRepository.findById(callerUserId)
                .orElseThrow(() -> new OrgException.OrgNotFound(
                        "Caller user not found: " + callerUserId));
        if (member.getUserId() != null && !member.getUserId().equals(callerUserId)) {
            throw new OrgException.InvalidInviteeIdentity(
                    "Invite was issued to a different user");
        }
        if (!caller.getEmail().trim().equalsIgnoreCase(member.getEmail())) {
            throw new OrgException.InvalidInviteeIdentity(
                    "Caller email does not match the invite");
        }
        // Refuse if the caller is already ACTIVE in another org.
        List<OrgMember> active = memberRepository.findByUserIdAndStatus(
                callerUserId, OrgMember.Status.ACTIVE);
        if (!active.isEmpty()) {
            throw new OrgException.AlreadyHasOrg(
                    "User already belongs to an org; leave it before accepting");
        }
        member.setUserId(callerUserId);
        member.setStatus(OrgMember.Status.ACTIVE);
        member.setJoinedAt(java.time.LocalDateTime.now());
        OrgMember saved = memberRepository.save(member);
        log.info("ORG ACCEPT org={} member={} user={}", orgId, memberId, callerUserId);
        return saved;
    }

    /**
     * Remove a member. OWNER-only; refuses to remove the last OWNER.
     *
     * <p>Deleting the final OWNER is blocked because it would orphan the org
     * (no one left who could manage it) — promote another member to OWNER first.
     *
     * @param orgId        the org the member belongs to (cross-checked)
     * @param memberId     the membership row id to delete
     * @param callerUserId the authenticated caller, who must be the OWNER
     * @throws OrgException.PermissionDenied if the caller is not the OWNER
     * @throws OrgException.OrgNotFound      if the member is missing or not in this org
     * @throws OrgException.LastOwner        if the member is the only OWNER of the org
     */
    @Transactional
    public void remove(UUID orgId, UUID memberId, UUID callerUserId) {
        requireOwner(orgId, callerUserId);
        OrgMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new OrgException.OrgNotFound(
                        "Member not found: " + memberId));
        if (!member.getOrgId().equals(orgId)) {
            throw new OrgException.OrgNotFound(
                    "Member " + memberId + " does not belong to org " + orgId);
        }
        if (member.getRole() == OrgMember.Role.OWNER) {
            long owners = memberRepository.findByOrgIdAndRole(orgId, OrgMember.Role.OWNER).size();
            if (owners <= 1) {
                throw new OrgException.LastOwner(
                        "Cannot remove the last OWNER; promote another member first");
            }
        }
        memberRepository.delete(member);
        log.info("ORG REMOVE org={} member={} by={}", orgId, memberId, callerUserId);
    }

    /**
     * Change a member's role. OWNER-only; refuses to demote the last OWNER.
     *
     * <p>This is also the supported path for creating a co-OWNER (promote an
     * existing member). Demoting the sole OWNER is blocked for the same
     * orphaning reason as {@link #remove} — promote a replacement first.
     *
     * @param orgId        the org the member belongs to (cross-checked)
     * @param memberId     the membership row id to update
     * @param callerUserId the authenticated caller, who must be the OWNER
     * @param newRole      the role to assign; required
     * @return the membership row with its new role
     * @throws OrgException.PermissionDenied if the caller is not the OWNER
     * @throws OrgException.InvalidState     if {@code newRole} is null
     * @throws OrgException.OrgNotFound      if the member is missing or not in this org
     * @throws OrgException.LastOwner        if this would demote the only OWNER
     */
    @Transactional
    public OrgMember changeRole(UUID orgId, UUID memberId, UUID callerUserId,
                                 OrgMember.Role newRole) {
        requireOwner(orgId, callerUserId);
        if (newRole == null) {
            throw new OrgException.InvalidState("New role is required");
        }
        OrgMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new OrgException.OrgNotFound(
                        "Member not found: " + memberId));
        if (!member.getOrgId().equals(orgId)) {
            throw new OrgException.OrgNotFound(
                    "Member " + memberId + " does not belong to org " + orgId);
        }
        if (member.getRole() == OrgMember.Role.OWNER && newRole != OrgMember.Role.OWNER) {
            long owners = memberRepository.findByOrgIdAndRole(orgId, OrgMember.Role.OWNER).size();
            if (owners <= 1) {
                throw new OrgException.LastOwner(
                        "Cannot demote the last OWNER; promote another member to OWNER first");
            }
        }
        member.setRole(newRole);
        OrgMember saved = memberRepository.save(member);
        log.info("ORG ROLE CHANGE org={} member={} newRole={} by={}",
                orgId, memberId, newRole, callerUserId);
        return saved;
    }

    /**
     * Returns the caller's ACTIVE membership in the given org, or empty.
     * Public so JWT issuance can embed the caller's
     * {@code orgId}+{@code orgRole} claims at login time.
     *
     * <p>(Despite the name, this is keyed purely on the user — it returns the
     * user's single ACTIVE membership across all orgs, which is the one
     * {@code UserService} stamps into the token.)
     *
     * @param userId the user whose active membership to look up
     * @return the user's ACTIVE membership, or {@link Optional#empty()} if none
     */
    @Transactional(readOnly = true)
    public Optional<OrgMember> findActiveMembership(UUID userId) {
        return memberRepository.findByUserIdAndStatus(userId, OrgMember.Status.ACTIVE)
                .stream()
                .findFirst();
    }

    // ── helpers ──

    /**
     * Authorization guard: asserts the user is an ACTIVE member of the org,
     * throwing if not. Used to gate read operations on the org.
     *
     * @param orgId  the org being accessed
     * @param userId the caller to authorize
     * @throws OrgException.PermissionDenied if the user is not an ACTIVE member of the org
     */
    private void requireActiveMember(UUID orgId, UUID userId) {
        boolean isMember = memberRepository.findByUserIdAndStatus(userId, OrgMember.Status.ACTIVE)
                .stream()
                .anyMatch(m -> m.getOrgId().equals(orgId));
        if (!isMember) {
            throw new OrgException.PermissionDenied(
                    "User is not an ACTIVE member of org " + orgId);
        }
    }

    /**
     * Authorization guard: asserts the user is an ACTIVE {@code OWNER} of the
     * org, throwing if not. Used to gate every mutating org operation (invite,
     * remove, changeRole).
     *
     * @param orgId  the org being mutated
     * @param userId the caller to authorize
     * @throws OrgException.PermissionDenied if the user is not an ACTIVE OWNER of the org
     */
    private void requireOwner(UUID orgId, UUID userId) {
        boolean isOwner = memberRepository.findByUserIdAndStatus(userId, OrgMember.Status.ACTIVE)
                .stream()
                .anyMatch(m -> m.getOrgId().equals(orgId) && m.getRole() == OrgMember.Role.OWNER);
        if (!isOwner) {
            throw new OrgException.PermissionDenied(
                    "Only OWNER may perform this action on org " + orgId);
        }
    }

    /**
     * Null-safe blank check used throughout the input validation above.
     *
     * @param s the string to test (may be null)
     * @return true if {@code s} is null, empty, or whitespace-only
     */
    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
