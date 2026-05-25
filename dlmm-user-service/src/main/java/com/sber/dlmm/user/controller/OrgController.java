package com.sber.dlmm.user.controller;

import com.sber.dlmm.user.entity.Org;
import com.sber.dlmm.user.entity.OrgMember;
import com.sber.dlmm.user.service.OrgService;
import io.swagger.v3.oas.annotations.Operation;
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
public class OrgController {

    private final OrgService orgService;

    /** Create a new org with the caller as OWNER. */
    @PostMapping
    @Operation(summary = "Create an org (caller becomes OWNER)")
    public ResponseEntity<OrgResponse> create(Authentication auth,
                                               @Valid @RequestBody CreateOrgRequest req) {
        UUID userId = (UUID) auth.getPrincipal();
        Org org = orgService.create(userId, req.name(), req.ownerEmail(), req.ownerName());
        return ResponseEntity.status(HttpStatus.CREATED).body(OrgResponse.from(org));
    }

    /** Org the caller belongs to (404 if none). */
    @GetMapping("/me")
    @Operation(summary = "Caller's current org (404 if not in an org)")
    public ResponseEntity<OrgResponse> me(Authentication auth) {
        UUID userId = (UUID) auth.getPrincipal();
        return orgService.findMyOrg(userId)
                .map(o -> ResponseEntity.ok(OrgResponse.from(o)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/members")
    @Operation(summary = "List members (only ACTIVE members of this org may read)")
    public ResponseEntity<List<MemberResponse>> listMembers(Authentication auth,
                                                             @PathVariable UUID id) {
        UUID userId = (UUID) auth.getPrincipal();
        List<MemberResponse> members = orgService.listMembers(id, userId).stream()
                .map(MemberResponse::from)
                .toList();
        return ResponseEntity.ok(members);
    }

    @PostMapping("/{id}/members")
    @Operation(summary = "Invite a new member (OWNER only)")
    public ResponseEntity<MemberResponse> invite(Authentication auth,
                                                  @PathVariable UUID id,
                                                  @Valid @RequestBody InviteRequest req) {
        UUID userId = (UUID) auth.getPrincipal();
        OrgMember member = orgService.invite(id, userId, req.email(), req.name(), req.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(MemberResponse.from(member));
    }

    @PutMapping("/{id}/members/{memberId}/role")
    @Operation(summary = "Change a member's role (OWNER only)")
    public ResponseEntity<MemberResponse> changeRole(Authentication auth,
                                                      @PathVariable UUID id,
                                                      @PathVariable UUID memberId,
                                                      @Valid @RequestBody RoleChangeRequest req) {
        UUID userId = (UUID) auth.getPrincipal();
        OrgMember saved = orgService.changeRole(id, memberId, userId, req.role());
        return ResponseEntity.ok(MemberResponse.from(saved));
    }

    @DeleteMapping("/{id}/members/{memberId}")
    @Operation(summary = "Remove a member (OWNER only)")
    public ResponseEntity<Void> remove(Authentication auth,
                                        @PathVariable UUID id,
                                        @PathVariable UUID memberId) {
        UUID userId = (UUID) auth.getPrincipal();
        orgService.remove(id, memberId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/members/{memberId}/accept")
    @Operation(summary = "Accept an invite (invitee only)")
    public ResponseEntity<MemberResponse> accept(Authentication auth,
                                                  @PathVariable UUID id,
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

    public record CreateOrgRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 255) String ownerEmail,
            @Size(max = 120) String ownerName) {}

    public record InviteRequest(
            @NotBlank @Size(max = 255) String email,
            @Size(max = 120) String name,
            @NotNull OrgMember.Role role) {}

    public record RoleChangeRequest(@NotNull OrgMember.Role role) {}

    public record OrgResponse(UUID id, String name, UUID ownerId, LocalDateTime createdAt) {
        public static OrgResponse from(Org o) {
            return new OrgResponse(o.getId(), o.getName(), o.getOwnerId(), o.getCreatedAt());
        }
    }

    public record MemberResponse(UUID id, UUID orgId, UUID userId, String email,
                                  String name, OrgMember.Role role,
                                  OrgMember.Status status, LocalDateTime joinedAt) {
        public static MemberResponse from(OrgMember m) {
            return new MemberResponse(m.getId(), m.getOrgId(), m.getUserId(),
                    m.getEmail(), m.getName(), m.getRole(), m.getStatus(), m.getJoinedAt());
        }
    }
}
