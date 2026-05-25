package com.sber.dlmm.user.controller;

import com.sber.dlmm.common.exception.OrgException;
import com.sber.dlmm.user.entity.Org;
import com.sber.dlmm.user.entity.OrgMember;
import com.sber.dlmm.user.service.OrgService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sprint 11 G-21 — controller-level unit tests for {@link OrgController}.
 *
 * <p>No Spring boot — the controller is exercised directly with a
 * mocked {@link OrgService}. This catches DTO mapping bugs (record →
 * entity translation, request validation passthrough) and the
 * principal-extraction conventions without paying the WebMvcTest
 * boot tax. Cross-cutting concerns (security gating, validation
 * binding) are covered by the e2e recipe in CLAUDE.md.
 */
class OrgControllerTest {

    private OrgService orgService;
    private OrgController controller;

    @BeforeEach
    void setUp() {
        orgService = mock(OrgService.class);
        controller = new OrgController(orgService);
    }

    @Test
    @DisplayName("GET /orgs/me — 404 when caller has no org")
    void getMyOrgNotFound() {
        UUID userId = UUID.randomUUID();
        when(orgService.findMyOrg(userId)).thenReturn(Optional.empty());

        ResponseEntity<OrgController.OrgResponse> response = controller.me(authFor(userId));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("GET /orgs/me — 200 + body when caller has an org")
    void getMyOrgFound() {
        UUID userId = UUID.randomUUID();
        Org org = Org.builder()
                .id(UUID.randomUUID())
                .name("Acme")
                .ownerId(userId)
                .createdAt(LocalDateTime.now())
                .build();
        when(orgService.findMyOrg(userId)).thenReturn(Optional.of(org));

        ResponseEntity<OrgController.OrgResponse> response = controller.me(authFor(userId));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().name()).isEqualTo("Acme");
        assertThat(response.getBody().id()).isEqualTo(org.getId());
    }

    @Test
    @DisplayName("POST /orgs — 201 + body, delegates to service.create with caller id")
    void createOrg() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        Org created = Org.builder()
                .id(orgId)
                .name("Acme")
                .ownerId(userId)
                .createdAt(LocalDateTime.now())
                .build();
        when(orgService.create(eq(userId), eq("Acme"), eq("creator@example.com"), eq("Creator")))
                .thenReturn(created);

        ResponseEntity<OrgController.OrgResponse> response = controller.create(
                authFor(userId),
                new OrgController.CreateOrgRequest("Acme", "creator@example.com", "Creator"));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(orgId);
        verify(orgService).create(userId, "Acme", "creator@example.com", "Creator");
    }

    @Test
    @DisplayName("POST /orgs/{id}/members — invite delegates to service")
    void invite() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        OrgMember member = OrgMember.builder()
                .id(UUID.randomUUID())
                .orgId(orgId)
                .email("newbie@example.com")
                .name("Newbie")
                .role(OrgMember.Role.FINANCE_MGR)
                .status(OrgMember.Status.PENDING)
                .joinedAt(LocalDateTime.now())
                .build();
        when(orgService.invite(eq(orgId), eq(userId), eq("newbie@example.com"), eq("Newbie"),
                eq(OrgMember.Role.FINANCE_MGR))).thenReturn(member);

        ResponseEntity<OrgController.MemberResponse> response = controller.invite(
                authFor(userId), orgId,
                new OrgController.InviteRequest("newbie@example.com", "Newbie", OrgMember.Role.FINANCE_MGR));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().role()).isEqualTo(OrgMember.Role.FINANCE_MGR);
        assertThat(response.getBody().status()).isEqualTo(OrgMember.Status.PENDING);
    }

    @Test
    @DisplayName("GET /orgs/{id}/members — passes caller id to service for auth")
    void listMembers() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        when(orgService.listMembers(orgId, userId)).thenReturn(List.of());

        ResponseEntity<List<OrgController.MemberResponse>> response = controller.listMembers(
                authFor(userId), orgId);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEmpty();
        verify(orgService).listMembers(orgId, userId);
    }

    @Test
    @DisplayName("DELETE /orgs/{id}/members/{memberId} — propagates service exceptions (404 path)")
    void removePropagatesNotFound() {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        // Service throws — controller must NOT swallow; GlobalExceptionHandler
        // (Spring-level, not exercised here) translates to the wire.
        org.mockito.Mockito.doThrow(new OrgException.OrgNotFound("not found"))
                .when(orgService).remove(orgId, memberId, userId);

        assertThatThrownBy(() -> controller.remove(authFor(userId), orgId, memberId))
                .isInstanceOf(OrgException.OrgNotFound.class);
    }

    /** Mirror the principal shape that the shared JwtAuthenticationFilter
     *  builds — UUID, kycStatus String, authorities. Tests only need the
     *  principal, so the rest is left empty. */
    private Authentication authFor(UUID userId) {
        return new UsernamePasswordAuthenticationToken(userId, "VERIFIED", List.of());
    }
}
