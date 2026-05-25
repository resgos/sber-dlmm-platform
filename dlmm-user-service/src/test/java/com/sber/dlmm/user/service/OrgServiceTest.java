package com.sber.dlmm.user.service;

import com.sber.dlmm.common.exception.OrgException;
import com.sber.dlmm.user.entity.Org;
import com.sber.dlmm.user.entity.OrgMember;
import com.sber.dlmm.user.entity.User;
import com.sber.dlmm.user.repository.OrgMemberRepository;
import com.sber.dlmm.user.repository.OrgRepository;
import com.sber.dlmm.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sprint 11 G-21 — pins the invariants in {@link OrgService}.
 *
 * <p>Tests use Mockito repository mocks driven by a tiny in-memory
 * row store ({@link MemberStore}). Repository fakes give us multi-call
 * sequencing (invite → list → remove) without the boilerplate of
 * stubbing each call individually; pure mocks are fine because the
 * service only uses a handful of repo methods and the store models
 * each.
 */
class OrgServiceTest {

    private static final UUID CREATOR = UUID.randomUUID();
    private static final String CREATOR_EMAIL = "creator@example.com";

    private OrgRepository orgRepo;
    private OrgMemberRepository memberRepo;
    private UserRepository userRepo;
    private OrgService service;
    private MemberStore memberStore;
    private Map<UUID, Org> orgsById;

    @BeforeEach
    void setUp() {
        orgRepo = mock(OrgRepository.class);
        memberRepo = mock(OrgMemberRepository.class);
        userRepo = mock(UserRepository.class);
        memberStore = new MemberStore();
        orgsById = new HashMap<>();
        wireOrgRepo();
        wireMemberRepo();
        // Default creator stub — individual tests override when they need
        // a different identity.
        when(userRepo.findById(CREATOR)).thenReturn(Optional.of(user(CREATOR, CREATOR_EMAIL, "Creator")));
        service = new OrgService(orgRepo, memberRepo, userRepo);
    }

    @Test
    @DisplayName("create — makes the creator the OWNER and a single ACTIVE member")
    void createMakesCreatorOwner() {
        Org org = service.create(CREATOR, "Acme", "creator@example.com", "Creator");

        assertThat(org.getName()).isEqualTo("Acme");
        assertThat(org.getOwnerId()).isEqualTo(CREATOR);
        List<OrgMember> members = memberRepo.findByOrgId(org.getId());
        assertThat(members).hasSize(1);
        assertThat(members.get(0).getRole()).isEqualTo(OrgMember.Role.OWNER);
        assertThat(members.get(0).getStatus()).isEqualTo(OrgMember.Status.ACTIVE);
        assertThat(members.get(0).getUserId()).isEqualTo(CREATOR);
    }

    @Test
    @DisplayName("create — rejected when caller already has an active membership")
    void createRejectedIfAlreadyInOrg() {
        service.create(CREATOR, "First", null, null);
        assertThatThrownBy(() -> service.create(CREATOR, "Second", null, null))
                .isInstanceOf(OrgException.AlreadyHasOrg.class);
    }

    @Test
    @DisplayName("invite — creates a PENDING member; OWNER-only")
    void invitePending() {
        Org org = service.create(CREATOR, "Acme", null, null);

        OrgMember invited = service.invite(
                org.getId(), CREATOR, "newbie@example.com", "Newbie", OrgMember.Role.FINANCE_MGR);

        assertThat(invited.getStatus()).isEqualTo(OrgMember.Status.PENDING);
        assertThat(invited.getEmail()).isEqualTo("newbie@example.com");
        assertThat(invited.getUserId()).isNull(); // invitee has no account yet
        assertThat(memberRepo.findByOrgId(org.getId())).hasSize(2);
    }

    @Test
    @DisplayName("invite — duplicate email rejected with ORG_MEMBER_EXISTS")
    void inviteDuplicateRejected() {
        Org org = service.create(CREATOR, "Acme", null, null);
        service.invite(org.getId(), CREATOR, "newbie@example.com", "Newbie", OrgMember.Role.FINANCE_MGR);

        assertThatThrownBy(() -> service.invite(
                org.getId(), CREATOR, "newbie@example.com", "Newbie2", OrgMember.Role.ACCOUNTANT))
                .isInstanceOf(OrgException.MemberAlreadyExists.class)
                .hasFieldOrPropertyWithValue("errorCode", "ORG_MEMBER_EXISTS");
    }

    @Test
    @DisplayName("invite — non-OWNER cannot invite")
    void inviteNonOwnerRejected() {
        Org org = service.create(CREATOR, "Acme", null, null);
        // Make a FINANCE_MGR member, then have them try to invite.
        UUID nonOwnerUser = UUID.randomUUID();
        memberStore.save(OrgMember.builder()
                .id(UUID.randomUUID())
                .orgId(org.getId())
                .userId(nonOwnerUser)
                .email("mgr@example.com")
                .name("Mgr")
                .role(OrgMember.Role.FINANCE_MGR)
                .status(OrgMember.Status.ACTIVE)
                .joinedAt(LocalDateTime.now())
                .build());

        assertThatThrownBy(() -> service.invite(
                org.getId(), nonOwnerUser, "newbie@example.com", "Newbie", OrgMember.Role.VIEWER))
                .isInstanceOf(OrgException.PermissionDenied.class);
    }

    @Test
    @DisplayName("accept — invitee flips PENDING → ACTIVE")
    void acceptFlipsState() {
        Org org = service.create(CREATOR, "Acme", null, null);
        OrgMember invited = service.invite(
                org.getId(), CREATOR, "newbie@example.com", "Newbie", OrgMember.Role.FINANCE_MGR);
        UUID newbieUserId = UUID.randomUUID();
        when(userRepo.findById(newbieUserId))
                .thenReturn(Optional.of(user(newbieUserId, "newbie@example.com", "Newbie")));

        OrgMember accepted = service.accept(org.getId(), invited.getId(), newbieUserId);

        assertThat(accepted.getStatus()).isEqualTo(OrgMember.Status.ACTIVE);
        assertThat(accepted.getUserId()).isEqualTo(newbieUserId);
    }

    @Test
    @DisplayName("remove — fails on the last OWNER")
    void removeLastOwnerRejected() {
        Org org = service.create(CREATOR, "Acme", null, null);
        UUID ownerMemberId = memberRepo.findByOrgId(org.getId()).get(0).getId();

        assertThatThrownBy(() -> service.remove(org.getId(), ownerMemberId, CREATOR))
                .isInstanceOf(OrgException.LastOwner.class)
                .hasFieldOrPropertyWithValue("errorCode", "ORG_LAST_OWNER");
    }

    @Test
    @DisplayName("changeRole — fails when demoting the last OWNER")
    void changeRoleLastOwnerRejected() {
        Org org = service.create(CREATOR, "Acme", null, null);
        UUID ownerMemberId = memberRepo.findByOrgId(org.getId()).get(0).getId();

        assertThatThrownBy(() -> service.changeRole(
                org.getId(), ownerMemberId, CREATOR, OrgMember.Role.FINANCE_MGR))
                .isInstanceOf(OrgException.LastOwner.class);
    }

    @Test
    @DisplayName("changeRole — succeeds for a non-OWNER")
    void changeRoleNonOwner() {
        Org org = service.create(CREATOR, "Acme", null, null);
        OrgMember invited = service.invite(
                org.getId(), CREATOR, "newbie@example.com", "Newbie", OrgMember.Role.FINANCE_MGR);

        OrgMember updated = service.changeRole(
                org.getId(), invited.getId(), CREATOR, OrgMember.Role.ACCOUNTANT);

        assertThat(updated.getRole()).isEqualTo(OrgMember.Role.ACCOUNTANT);
    }

    @Test
    @DisplayName("findMyOrg — returns empty when caller has no membership")
    void findMyOrgEmpty() {
        Optional<Org> result = service.findMyOrg(UUID.randomUUID());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findMyOrg — returns the org for an ACTIVE member")
    void findMyOrgPresent() {
        Org org = service.create(CREATOR, "Acme", null, null);
        Optional<Org> result = service.findMyOrg(CREATOR);
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(org.getId());
    }

    // ── wiring ──

    private void wireOrgRepo() {
        when(orgRepo.save(any(Org.class))).thenAnswer(inv -> {
            Org o = inv.getArgument(0);
            if (o.getId() == null) o.setId(UUID.randomUUID());
            if (o.getCreatedAt() == null) o.setCreatedAt(LocalDateTime.now());
            orgsById.put(o.getId(), o);
            return o;
        });
        when(orgRepo.findById(any(UUID.class))).thenAnswer(inv ->
                Optional.ofNullable(orgsById.get((UUID) inv.getArgument(0))));
    }

    private void wireMemberRepo() {
        when(memberRepo.save(any(OrgMember.class))).thenAnswer(inv -> memberStore.save(inv.getArgument(0)));
        when(memberRepo.findById(any(UUID.class))).thenAnswer(inv -> memberStore.findById(inv.getArgument(0)));
        when(memberRepo.findByOrgId(any(UUID.class))).thenAnswer(inv -> memberStore.findByOrgId(inv.getArgument(0)));
        when(memberRepo.findByOrgIdAndRole(any(UUID.class), any(OrgMember.Role.class)))
                .thenAnswer(inv -> memberStore.findByOrgIdAndRole(inv.getArgument(0), inv.getArgument(1)));
        when(memberRepo.findByUserIdAndStatus(any(UUID.class), any(OrgMember.Status.class)))
                .thenAnswer(inv -> memberStore.findByUserIdAndStatus(inv.getArgument(0), inv.getArgument(1)));
        when(memberRepo.findByOrgIdAndEmail(any(UUID.class), any(String.class)))
                .thenAnswer(inv -> memberStore.findByOrgIdAndEmail(inv.getArgument(0), inv.getArgument(1)));
    }

    private static User user(UUID id, String email, String firstName) {
        return User.builder()
                .id(id)
                .sberId("SBER-" + id.toString().substring(0, 8))
                .email(email)
                .phone("+70000000000")
                .firstName(firstName)
                .lastName("L")
                .passwordHash("x")
                .build();
    }

    /** Tiny in-memory store backing the member repo mocks. */
    private static class MemberStore {
        final Map<UUID, OrgMember> rows = new HashMap<>();

        OrgMember save(OrgMember m) {
            if (m.getId() == null) m.setId(UUID.randomUUID());
            if (m.getJoinedAt() == null) m.setJoinedAt(LocalDateTime.now());
            rows.put(m.getId(), m);
            return m;
        }

        Optional<OrgMember> findById(UUID id) { return Optional.ofNullable(rows.get(id)); }

        List<OrgMember> findByOrgId(UUID orgId) {
            return rows.values().stream().filter(m -> orgId.equals(m.getOrgId())).toList();
        }

        List<OrgMember> findByOrgIdAndRole(UUID orgId, OrgMember.Role role) {
            return rows.values().stream()
                    .filter(m -> orgId.equals(m.getOrgId()) && role == m.getRole())
                    .toList();
        }

        List<OrgMember> findByUserIdAndStatus(UUID userId, OrgMember.Status status) {
            return rows.values().stream()
                    .filter(m -> userId.equals(m.getUserId()) && status == m.getStatus())
                    .toList();
        }

        Optional<OrgMember> findByOrgIdAndEmail(UUID orgId, String email) {
            return rows.values().stream()
                    .filter(m -> orgId.equals(m.getOrgId()) && email.equalsIgnoreCase(m.getEmail()))
                    .findFirst();
        }
    }
}
