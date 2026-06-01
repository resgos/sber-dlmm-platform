package com.sber.dlmm.user.controller;

import com.sber.dlmm.user.entity.Org;
import com.sber.dlmm.user.entity.OrgMember;
import com.sber.dlmm.user.service.OrgService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Sprint 11 G-21 — REST surface for organisation management. All
 * mutation endpoints rely on {@link OrgService} for permission /
 * invariant enforcement; this controller is a thin adapter.
 *
 * <p>The Spring Cloud Gateway route {@code orgs-service} forwards
 * {@code /api/v1/orgs/**} here.
 */
@RestController
@RequestMapping("/api/v1/orgs")
@RequiredArgsConstructor
@Tag(name = "Organisations", description = "Organisation management and role-based team membership")
public class OrgController {

    private final OrgService orgService;

    /**
     * Create a new org with the caller as OWNER.
     *
     * <p>The body's owner email/name are convenience defaults that
     * {@link OrgService#create} cross-checks against the caller's stored
     * profile, so they can't be used to spoof an identity.
     *
     * @param auth the authentication whose principal is the caller's user id
     * @param req  the validated create request (name + optional owner email/name)
     * @return 201 with the created org
     */
    @PostMapping
    @Operation(summary = "Create an org (caller becomes OWNER)",
            description = "Creates a new organisation with the authenticated caller as its sole OWNER. "
                    + "The body's ownerEmail/ownerName are convenience defaults cross-checked against the "
                    + "caller's stored profile. Any authenticated user may call, but the caller must not already "
                    + "be an ACTIVE member of another org.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Org created; caller is the OWNER"),
            @ApiResponse(responseCode = "400", description = "Validation error or blank org name"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "409", description = "Caller already belongs to an org")
    })
    public ResponseEntity<OrgResponse> create(Authentication auth,
                                               @Valid @RequestBody CreateOrgRequest req) {
        UUID userId = (UUID) auth.getPrincipal();
        Org org = orgService.create(userId, req.name(), req.ownerEmail(), req.ownerName());
        return ResponseEntity.status(HttpStatus.CREATED).body(OrgResponse.from(org));
    }

    /**
     * Org the caller belongs to (404 if none).
     *
     * @param auth the authentication whose principal is the caller's user id
     * @return 200 with the caller's org, or 404 if they have no active membership
     */
    @GetMapping("/me")
    @Operation(summary = "Caller's current org (404 if not in an org)",
            description = "Returns the organisation the authenticated caller is an ACTIVE member of. "
                    + "Responds 404 when the caller has no active membership.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Caller's organisation"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "404", description = "Caller is not a member of any org")
    })
    public ResponseEntity<OrgResponse> me(Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return orgService.findMyOrg(userId)
                .map(o -> ResponseEntity.ok(OrgResponse.from(o)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Lists all members of an org. The caller must be an ACTIVE member of that
     * org ({@link OrgService#listMembers} enforces it), so rosters aren't
     * readable across tenants.
     *
     * @param auth the authentication whose principal is the caller's user id
     * @param id   the organisation id
     * @return 200 with the org's members projected to {@link MemberResponse}
     */
    @GetMapping("/{id}/members")
    @Operation(summary = "List members (only ACTIVE members of this org may read)",
            description = "Returns all members (any status) of the org. The caller must be an ACTIVE "
                    + "member of this org, otherwise the request is rejected with 403.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Members of the org"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller is not an ACTIVE member of this org")
    })
    public ResponseEntity<List<MemberResponse>> listMembers(Authentication auth,
                                                             @Parameter(description = "Organisation id")
                                                             @PathVariable UUID id) {
        UUID userId = (UUID) auth.getPrincipal();
        List<MemberResponse> members = orgService.listMembers(id, userId).stream()
                .map(MemberResponse::from)
                .toList();
        return ResponseEntity.ok(members);
    }

    /**
     * Invites a new member (creates a PENDING membership). OWNER-only; the role
     * may not be OWNER and the email must not already be a member
     * ({@link OrgService#invite} enforces this).
     *
     * @param auth the authentication whose principal is the caller's user id
     * @param id   the organisation id
     * @param req  the validated invite (email, optional name, role)
     * @return 201 with the created PENDING membership
     */
    @PostMapping("/{id}/members")
    @Operation(summary = "Invite a new member (OWNER only)",
            description = "Creates a PENDING membership for the given email and role. OWNER-only. "
                    + "The role may not be OWNER (promote an existing member instead), and the email "
                    + "must not already be a member of this org.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Invite created (PENDING membership)"),
            @ApiResponse(responseCode = "400", description = "Validation error, blank email, or attempt to invite as OWNER"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller is not the OWNER of this org"),
            @ApiResponse(responseCode = "409", description = "Email is already a member of this org")
    })
    public ResponseEntity<MemberResponse> invite(Authentication auth,
                                                  @Parameter(description = "Organisation id")
                                                  @PathVariable UUID id,
                                                  @Valid @RequestBody InviteRequest req) {
        UUID userId = (UUID) auth.getPrincipal();
        OrgMember member = orgService.invite(id, userId, req.email(), req.name(), req.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(MemberResponse.from(member));
    }

    /**
     * Changes a member's role. OWNER-only; demoting the last OWNER is rejected
     * ({@link OrgService#changeRole} enforces this). Also the path to create a
     * co-OWNER by promotion.
     *
     * @param auth     the authentication whose principal is the caller's user id
     * @param id       the organisation id
     * @param memberId the target membership id
     * @param req      the validated request carrying the new role
     * @return 200 with the updated membership
     */
    @PutMapping("/{id}/members/{memberId}/role")
    @Operation(summary = "Change a member's role (OWNER only)",
            description = "Updates the target member's role within the org. OWNER-only. Demoting the "
                    + "last remaining OWNER is rejected — promote another member to OWNER first.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role updated"),
            @ApiResponse(responseCode = "400", description = "Validation error or missing role"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller is not the OWNER of this org"),
            @ApiResponse(responseCode = "404", description = "Member not found or not in this org"),
            @ApiResponse(responseCode = "409", description = "Would demote the last OWNER")
    })
    public ResponseEntity<MemberResponse> changeRole(Authentication auth,
                                                      @Parameter(description = "Organisation id")
                                                      @PathVariable UUID id,
                                                      @Parameter(description = "Target member id")
                                                      @PathVariable UUID memberId,
                                                      @Valid @RequestBody RoleChangeRequest req) {
        UUID userId = (UUID) auth.getPrincipal();
        OrgMember saved = orgService.changeRole(id, memberId, userId, req.role());
        return ResponseEntity.ok(MemberResponse.from(saved));
    }

    /**
     * Removes a member from the org. OWNER-only; removing the last OWNER is
     * rejected to avoid orphaning the org ({@link OrgService#remove} enforces
     * this).
     *
     * @param auth     the authentication whose principal is the caller's user id
     * @param id       the organisation id
     * @param memberId the target membership id to remove
     * @return 204 No Content on success
     */
    @DeleteMapping("/{id}/members/{memberId}")
    @Operation(summary = "Remove a member (OWNER only)",
            description = "Deletes the target membership from the org. OWNER-only. Removing the last "
                    + "remaining OWNER is rejected to avoid orphaning the org.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Member removed"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller is not the OWNER of this org"),
            @ApiResponse(responseCode = "404", description = "Member not found or not in this org"),
            @ApiResponse(responseCode = "409", description = "Would remove the last OWNER")
    })
    public ResponseEntity<Void> remove(Authentication auth,
                                        @Parameter(description = "Organisation id")
                                        @PathVariable UUID id,
                                        @Parameter(description = "Target member id")
                                        @PathVariable UUID memberId) {
        UUID userId = (UUID) auth.getPrincipal();
        orgService.remove(id, memberId, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Accepts a pending invite, flipping the membership PENDING → ACTIVE. Only
     * the invitee may accept — their profile email must match the invite and
     * they must not already belong to another org ({@link OrgService#accept}
     * enforces both).
     *
     * @param auth     the authentication whose principal is the caller's user id
     * @param id       the organisation id
     * @param memberId the membership id from the invite
     * @return 200 with the now-ACTIVE membership
     */
    @PostMapping("/{id}/members/{memberId}/accept")
    @Operation(summary = "Accept an invite (invitee only)",
            description = "Flips a PENDING membership to ACTIVE. Only the invitee may accept — the "
                    + "caller's profile email must match the invite. The caller must not already be an "
                    + "ACTIVE member of another org.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invite accepted; membership is now ACTIVE"),
            @ApiResponse(responseCode = "400", description = "Membership is not in PENDING state"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid authentication"),
            @ApiResponse(responseCode = "403", description = "Caller is not the invitee (identity/email mismatch)"),
            @ApiResponse(responseCode = "404", description = "Member or caller not found, or member not in this org"),
            @ApiResponse(responseCode = "409", description = "Caller already belongs to an org")
    })
    public ResponseEntity<MemberResponse> accept(Authentication auth,
                                                  @Parameter(description = "Organisation id")
                                                  @PathVariable UUID id,
                                                  @Parameter(description = "Membership id from the invite")
                                                  @PathVariable UUID memberId) {
        UUID userId = (UUID) auth.getPrincipal();
        OrgMember saved = orgService.accept(id, memberId, userId);
        return ResponseEntity.ok(MemberResponse.from(saved));
    }

    // ── DTOs ──
    //
    // Kept as records inside the controller because they're tightly
    // coupled to the wire format; the entity classes have JPA
    // baggage (UUID generation, lombok @Builder) that we don't want
    // to bleed onto the API. The frontend type in
    // dlmm-user-ui/src/store/teamStore.ts mirrors these record shapes.

    /**
     * Create-org request body.
     *
     * @param name       the organisation name (required, ≤200 chars)
     * @param ownerEmail optional owner email default (≤255 chars); cross-checked against the caller
     * @param ownerName  optional owner display-name default (≤120 chars)
     */
    public record CreateOrgRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 255) String ownerEmail,
            @Size(max = 120) String ownerName) {}

    /**
     * Invite-member request body.
     *
     * @param email the invitee's email (required, ≤255 chars)
     * @param name  optional display name (≤120 chars); defaults to the email when blank
     * @param role  the role to grant (required; must not be OWNER)
     */
    public record InviteRequest(
            @NotBlank @Size(max = 255) String email,
            @Size(max = 120) String name,
            @NotNull OrgMember.Role role) {}

    /**
     * Change-role request body.
     *
     * @param role the new role to assign (required)
     */
    public record RoleChangeRequest(@NotNull OrgMember.Role role) {}

    /**
     * Wire projection of an {@link Org}.
     *
     * @param id        the organisation id
     * @param name      the organisation name
     * @param ownerId   the user id of the org's owner
     * @param createdAt when the org was created
     */
    public record OrgResponse(UUID id, String name, UUID ownerId, LocalDateTime createdAt) {
        /**
         * Maps an {@link Org} entity to its wire projection.
         *
         * @param o the entity to project
         * @return the corresponding {@link OrgResponse}
         */
        public static OrgResponse from(Org o) {
            return new OrgResponse(o.getId(), o.getName(), o.getOwnerId(), o.getCreatedAt());
        }
    }

    /**
     * Wire projection of an {@link OrgMember}.
     *
     * @param id       the membership row id
     * @param orgId    the organisation the membership belongs to
     * @param userId   the member's user id (null for an unaccepted invite with no pre-resolved account)
     * @param email    the member's (invite) email
     * @param name     the member's display name
     * @param role     the member's role within the org
     * @param status   the membership status (PENDING / ACTIVE)
     * @param joinedAt when the member accepted (null while PENDING)
     */
    public record MemberResponse(UUID id, UUID orgId, UUID userId, String email,
                                  String name, OrgMember.Role role,
                                  OrgMember.Status status, LocalDateTime joinedAt) {
        /**
         * Maps an {@link OrgMember} entity to its wire projection.
         *
         * @param m the entity to project
         * @return the corresponding {@link MemberResponse}
         */
        public static MemberResponse from(OrgMember m) {
            return new MemberResponse(m.getId(), m.getOrgId(), m.getUserId(),
                    m.getEmail(), m.getName(), m.getRole(), m.getStatus(), m.getJoinedAt());
        }
    }
}
