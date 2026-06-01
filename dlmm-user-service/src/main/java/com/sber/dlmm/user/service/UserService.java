package com.sber.dlmm.user.service;

import com.sber.dlmm.common.dto.PageResponse;
import com.sber.dlmm.common.enums.KycStatus;
import com.sber.dlmm.common.enums.UserRole;
import com.sber.dlmm.common.exception.ForbiddenException;
import com.sber.dlmm.common.exception.UnauthorizedException;
import com.sber.dlmm.common.exception.UserAlreadyExistsException;
import com.sber.dlmm.common.exception.UserNotFoundException;
import com.sber.dlmm.user.dto.AuthResponse;
import com.sber.dlmm.user.dto.LoginRequest;
import com.sber.dlmm.user.dto.RefreshTokenRequest;
import com.sber.dlmm.user.dto.RegisterRequest;
import com.sber.dlmm.user.dto.UpdateKycRequest;
import com.sber.dlmm.user.dto.UpdateProfileRequest;
import com.sber.dlmm.user.dto.UpdateRoleRequest;
import com.sber.dlmm.user.dto.UserProfileResponse;
import com.sber.dlmm.user.entity.OrgMember;
import com.sber.dlmm.user.entity.User;
import com.sber.dlmm.user.event.KafkaProducerService;
import com.sber.dlmm.user.event.UserBlockedEvent;
import com.sber.dlmm.user.event.UserCreatedEvent;
import com.sber.dlmm.user.event.UserKycVerifiedEvent;
import com.sber.dlmm.user.repository.UserRepository;
import com.sber.dlmm.common.security.JwtRevocationService;
import com.sber.dlmm.user.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Core application service for the identity domain — registration, login,
 * token refresh, logout/revocation, profile CRUD, KYC and role administration,
 * and the user-search/block/unblock admin operations. This is the orchestration
 * layer behind {@code UserController}; it owns the business rules while the
 * controller is a thin HTTP adapter.
 *
 * <p>Security-critical responsibilities concentrated here:
 * <ul>
 *   <li><b>Password handling</b> — passwords are only ever stored as BCrypt
 *       hashes (via the injected {@link PasswordEncoder}); the plaintext is
 *       hashed at registration and compared with {@code matches} at login,
 *       never logged or persisted.</li>
 *   <li><b>Token issuance</b> — every successful register/login/refresh mints
 *       a fresh access+refresh pair through {@link JwtTokenProvider}. Access
 *       tokens are funnelled through {@link #issueAccessToken} so org claims
 *       are embedded consistently.</li>
 *   <li><b>Logout / revocation</b> — {@link #logout} writes the token
 *       {@code jti} to the Redis denylist so a presented token stops working
 *       before its natural expiry.</li>
 *   <li><b>Login response leakage</b> — login failures (unknown email vs.
 *       wrong password) intentionally throw the <em>same</em> message so the
 *       endpoint can't be used to enumerate registered emails.</li>
 * </ul>
 *
 * <p>Most mutating methods are {@code @Transactional}; read paths are
 * {@code @Transactional(readOnly = true)}. KYC-verified and block transitions
 * additionally publish Kafka events via {@link KafkaProducerService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final KafkaProducerService kafkaProducerService;
    private final JwtRevocationService jwtRevocationService;
    /**
     * Sprint 11 G-21 — looked up at token-issue time to embed the
     * caller's {@code orgId} + {@code orgRole} claims. Setter injection
     * (rather than ctor) so the UserService bean wires even if the
     * org module hasn't loaded yet — and to keep the existing
     * {@code @RequiredArgsConstructor} signature stable for the rest
     * of the codebase / tests.
     */
    @Autowired(required = false)
    private OrgService orgService;

    /**
     * Registers a brand-new account and immediately logs it in.
     *
     * <p>Enforces uniqueness on both {@code sberId} and {@code email} (checked
     * up front so the caller gets a clean 409 rather than a constraint
     * violation). The password is BCrypt-hashed before persistence; the new
     * user starts with role {@code USER} and KYC {@code PENDING}. On success a
     * {@code user.created} Kafka event is emitted and an access+refresh token
     * pair is issued so the client is authenticated without a second round-trip.
     *
     * @param req the registration payload (sberId, email, phone, names, raw password)
     * @return the issued tokens, their expiry, and the new user's profile
     * @throws UserAlreadyExistsException if the sberId or email is already taken
     */
    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (userRepository.existsBySberId(req.sberId())) {
            throw new UserAlreadyExistsException("User with sberId " + req.sberId() + " already exists");
        }
        if (userRepository.existsByEmail(req.email())) {
            throw new UserAlreadyExistsException("User with email " + req.email() + " already exists");
        }

        User user = User.builder()
                .sberId(req.sberId())
                .email(req.email())
                .phone(req.phone())
                .firstName(req.firstName())
                .lastName(req.lastName())
                .passwordHash(passwordEncoder.encode(req.password()))
                .kycStatus(KycStatus.PENDING)
                .role(UserRole.USER)
                .build();

        user = userRepository.save(user);
        log.info("User registered: id={}, sberId={}", user.getId(), user.getSberId());

        kafkaProducerService.sendUserCreated(new UserCreatedEvent(
                user.getId(), user.getSberId(), user.getEmail(), user.getCreatedAt()
        ));

        String accessToken = issueAccessToken(user);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        return new AuthResponse(
                accessToken,
                refreshToken,
                jwtTokenProvider.getAccessTokenExpirySeconds(),
                toProfileResponse(user)
        );
    }

    /**
     * Authenticates by email + password and issues a fresh token pair.
     *
     * <p>Security note: a missing user and a wrong password both throw
     * {@link UnauthorizedException} with the <em>identical</em> Russian
     * message ("Неверный email или пароль") so the response can't be used to
     * tell whether an email is registered (no user-enumeration oracle). On
     * success {@code last_login_at} is stamped (used by the engagement-score
     * analytics) and an access+refresh pair is returned.
     *
     * @param req the login payload (email + raw password)
     * @return the issued tokens, their expiry, and the user's profile
     * @throws UnauthorizedException if the email is unknown or the password does not match
     */
    @Transactional
    public AuthResponse login(LoginRequest req) {
        User user = userRepository.findByEmail(req.email())
                .orElseThrow(() -> new UnauthorizedException("Неверный email или пароль"));

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("Неверный email или пароль");
        }

        // Batch #6 — populate last_login_at для engagement-score query
        // (PilotHealthService B-06). Real auth flow now stays in sync с
        // seed backfill вместо завися от docker/07-seed-fix-backend-bugs.sql.
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        String accessToken = issueAccessToken(user);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        return new AuthResponse(
                accessToken,
                refreshToken,
                jwtTokenProvider.getAccessTokenExpirySeconds(),
                toProfileResponse(user)
        );
    }

    /**
     * Exchanges a valid refresh token for a brand-new access+refresh pair
     * (token rotation).
     *
     * <p>Three guards run in order: the token must verify (signature + expiry),
     * it must actually be a refresh token (a misused access token is rejected),
     * and the user it references must still exist. The new tokens are built
     * from the user's <em>current</em> role/KYC, so privilege changes made
     * since the original login take effect on the next refresh. Not
     * {@code @Transactional} — it only reads the user row.
     *
     * @param req the request carrying the refresh token string
     * @return a freshly issued token pair plus the user's profile
     * @throws UnauthorizedException if the token is invalid, expired, or not a refresh token
     * @throws UserNotFoundException if the token's user no longer exists
     */
    public AuthResponse refresh(RefreshTokenRequest req) {
        if (!jwtTokenProvider.validateToken(req.refreshToken())) {
            throw new UnauthorizedException("Invalid refresh token");
        }
        if (!jwtTokenProvider.isRefreshToken(req.refreshToken())) {
            throw new UnauthorizedException("Token is not a refresh token");
        }

        UUID userId = jwtTokenProvider.getUserId(req.refreshToken());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));

        String accessToken = issueAccessToken(user);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        return new AuthResponse(
                accessToken,
                refreshToken,
                jwtTokenProvider.getAccessTokenExpirySeconds(),
                toProfileResponse(user)
        );
    }

    /**
     * Sprint 11 G-21 — single chokepoint for embedding org claims into
     * the access token. Falls through to the no-org overload when
     * {@link OrgService} is absent (e.g. test contexts that wire only
     * UserService) or the user has no ACTIVE membership.
     *
     * <p>Looking up the active org membership here (rather than in the caller)
     * guarantees every issued access token carries the same {@code orgId} +
     * {@code orgRole} claims, regardless of which entry point minted it.
     *
     * @param user the user the token is being issued for
     * @return a signed access token carrying role, KYC, and (when present) org claims
     */
    private String issueAccessToken(User user) {
        UUID orgId = null;
        String orgRole = null;
        if (orgService != null) {
            Optional<OrgMember> active = orgService.findActiveMembership(user.getId());
            if (active.isPresent()) {
                orgId = active.get().getOrgId();
                orgRole = active.get().getRole().name();
            }
        }
        return jwtTokenProvider.generateAccessToken(
                user.getId(), user.getRole(), user.getKycStatus(), orgId, orgRole);
    }

    /**
     * Sprint 8 AU-3 — revoke the supplied access token (and refresh token if
     * provided) so subsequent presentations are rejected by
     * {@code JwtAuthenticationFilter}'s denylist check.
     *
     * <p>Idempotent: re-calling logout with the same tokens is a no-op (Redis
     * SET overwrites with the same TTL).
     *
     * @param accessToken  the Bearer token from the logout request (required)
     * @param refreshToken optional refresh token (null = client can't supply
     *                     one, e.g. SSO sessions). If supplied, also revoked
     *                     so the user can't refresh their way back in.
     */
    public void logout(String accessToken, String refreshToken) {
        revokeIfValid(accessToken, "access");
        if (refreshToken != null && !refreshToken.isBlank()) {
            revokeIfValid(refreshToken, "refresh");
        }
    }

    /**
     * Best-effort revocation of a single token: validate it, read its
     * {@code jti} and remaining TTL, and write that jti to the Redis denylist
     * for exactly the time left until the token would have expired naturally
     * (so the denylist entry self-cleans and never grows unbounded).
     *
     * <p>Deliberately swallows all failure modes — a malformed token, a
     * legacy token with no {@code jti}, or even an unexpected parse error — and
     * only logs. Logout is a client-driven "I'm done" action; it must never
     * surface an error back to the caller, and a token we can't parse has no
     * jti to revoke anyway.
     *
     * @param token the raw JWT string to attempt to revoke
     * @param label short tag ("access"/"refresh") used only in log messages
     */
    private void revokeIfValid(String token, String label) {
        if (!jwtTokenProvider.validateToken(token)) {
            // Don't 500 on a malformed token — client already wants to log out;
            // just log and move on. A malformed token has no jti to revoke anyway.
            log.debug("Logout: skipping malformed {} token", label);
            return;
        }
        try {
            String jti = jwtTokenProvider.parseClaims(token).getId();
            if (jti == null || jti.isBlank()) {
                log.debug("Logout: {} token has no jti (legacy pre-AU-3 token); skipping denylist write", label);
                return;
            }
            long expEpoch = jwtTokenProvider.parseClaims(token).getExpiration().toInstant().getEpochSecond();
            long nowEpoch = java.time.Instant.now().getEpochSecond();
            long ttl = expEpoch - nowEpoch;
            jwtRevocationService.revoke(jti, ttl);
            log.info("Logout: revoked {} jti={} (TTL {}s)", label, jti, ttl);
        } catch (Exception ex) {
            // Parsing failed even though validateToken returned true — should
            // not happen, but log loudly so we notice if it ever does.
            log.error("Logout: failed to revoke {} token: {}", label, ex.getMessage());
        }
    }

    /**
     * Loads a user's profile by id.
     *
     * @param userId the id of the user to fetch
     * @return the user's profile projection
     * @throws UserNotFoundException if no user exists with that id
     */
    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));
        return toProfileResponse(user);
    }

    /**
     * Used by pool-engine for KYC pre-checks before swap / add-liquidity.
     *
     * <p>Returns false (not an exception) for an unknown user, so the caller's
     * gate is naturally fail-closed: an unverifiable user is treated as not
     * KYC'd.
     *
     * @param userId the id of the user to check
     * @return true only if the user exists and their KYC status is {@code VERIFIED}
     */
    @Transactional(readOnly = true)
    public boolean isKycVerified(UUID userId) {
        return userRepository.findById(userId)
                .map(u -> u.getKycStatus() == com.sber.dlmm.common.enums.KycStatus.VERIFIED)
                .orElse(false);
    }

    /**
     * Applies a partial profile update. Only the non-null fields of the request
     * are written (email, phone, first name, last name) — a null field leaves
     * the stored value untouched, giving PATCH-style semantics over a PUT.
     *
     * @param userId the id of the user to update
     * @param req    the partial update; null fields are skipped
     * @return the updated profile projection
     * @throws UserNotFoundException if no user exists with that id
     */
    @Transactional
    public UserProfileResponse updateProfile(UUID userId, UpdateProfileRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));

        if (req.email() != null) {
            user.setEmail(req.email());
        }
        if (req.phone() != null) {
            user.setPhone(req.phone());
        }
        if (req.firstName() != null) {
            user.setFirstName(req.firstName());
        }
        if (req.lastName() != null) {
            user.setLastName(req.lastName());
        }

        user = userRepository.save(user);
        log.info("User profile updated: id={}", user.getId());
        return toProfileResponse(user);
    }

    /**
     * Sets a user's KYC status (admin operation behind the audited controller
     * endpoint). When — and only when — the new status is {@code VERIFIED}, a
     * {@code user.kyc.verified} Kafka event is emitted so downstream services
     * can unlock KYC-gated features. Other transitions persist silently.
     *
     * @param userId the id of the user whose KYC is being changed
     * @param req    the request carrying the target KYC status
     * @return the updated profile projection
     * @throws UserNotFoundException if no user exists with that id
     */
    @Transactional
    public UserProfileResponse updateKycStatus(UUID userId, UpdateKycRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));

        user.setKycStatus(req.kycStatus());
        user = userRepository.save(user);
        log.info("User KYC status updated: id={}, status={}", user.getId(), req.kycStatus());

        if (req.kycStatus() == KycStatus.VERIFIED) {
            kafkaProducerService.sendUserKycVerified(new UserKycVerifiedEvent(
                    user.getId(), LocalDateTime.now()
            ));
        }

        return toProfileResponse(user);
    }

    /**
     * Changes a user's platform role (SUPER_ADMIN-only operation behind the
     * audited controller endpoint). The new role takes full effect on the
     * user's next token issuance (login or refresh); already-issued access
     * tokens keep their old role claim until they expire.
     *
     * @param userId the id of the user whose role is being changed
     * @param req    the request carrying the target role
     * @return the updated profile projection
     * @throws UserNotFoundException if no user exists with that id
     */
    @Transactional
    public UserProfileResponse updateRole(UUID userId, UpdateRoleRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));

        user.setRole(req.role());
        user = userRepository.save(user);
        log.info("User role updated: id={}, role={}", user.getId(), req.role());
        return toProfileResponse(user);
    }

    /**
     * Paged admin user search. Filters are applied in strict priority order —
     * only the first non-empty one is used: free-text {@code query} (name /
     * email) &gt; {@code kycStatus} &gt; {@code role} &gt; otherwise all users.
     * Results are always sorted newest-first by creation time.
     *
     * @param query     free-text search over name/email; highest priority when non-blank
     * @param kycStatus KYC filter, applied only when {@code query} is blank
     * @param role      role filter, applied only when {@code query} and {@code kycStatus} are null
     * @param page      zero-based page index
     * @param size      page size
     * @return a page of user profiles plus paging metadata
     */
    @Transactional(readOnly = true)
    public PageResponse<UserProfileResponse> searchUsers(String query, KycStatus kycStatus,
                                                          UserRole role, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<User> userPage;

        if (query != null && !query.isBlank()) {
            userPage = userRepository.search(query, pageable);
        } else if (kycStatus != null) {
            userPage = userRepository.findByKycStatus(kycStatus, pageable);
        } else if (role != null) {
            userPage = userRepository.findByRole(role, pageable);
        } else {
            userPage = userRepository.findAll(pageable);
        }

        return new PageResponse<>(
                userPage.getContent().stream().map(this::toProfileResponse).toList(),
                userPage.getNumber(),
                userPage.getSize(),
                userPage.getTotalElements(),
                userPage.getTotalPages()
        );
    }

    /**
     * Blocks a user: demotes them to {@code USER} and forces KYC to
     * {@code REJECTED} (which gates them out of KYC-protected flows), then
     * emits a {@code user.blocked} Kafka event. A {@code SUPER_ADMIN} target is
     * protected — attempting to block one is refused — so the platform can't be
     * locked out of its own top-level admin.
     *
     * @param userId the id of the user to block
     * @throws UserNotFoundException if no user exists with that id
     * @throws ForbiddenException    if the target user is a {@code SUPER_ADMIN}
     */
    @Transactional
    public void blockUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));

        if (user.getRole() == UserRole.SUPER_ADMIN) {
            throw new ForbiddenException("Cannot block a SUPER_ADMIN user");
        }

        user.setRole(UserRole.USER);
        user.setKycStatus(KycStatus.REJECTED);
        userRepository.save(user);
        log.info("User blocked: id={}", userId);

        kafkaProducerService.sendUserBlocked(new UserBlockedEvent(
                userId, LocalDateTime.now()
        ));
    }

    /**
     * Reverses a block by resetting the user's KYC status to {@code PENDING}
     * (re-opening the verification path). Note this does <em>not</em> restore
     * any role that {@link #blockUser} stripped — the user comes back as a
     * plain {@code USER} and any elevated role must be re-granted explicitly.
     * Emits no event.
     *
     * @param userId the id of the user to unblock
     * @throws UserNotFoundException if no user exists with that id
     */
    @Transactional
    public void unblockUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));

        user.setKycStatus(KycStatus.PENDING);
        userRepository.save(user);
        log.info("User unblocked: id={}", userId);
    }

    /**
     * Maps a {@link User} entity to its outward-facing
     * {@link UserProfileResponse} projection. Central mapping helper so every
     * endpoint returns the same shape; deliberately omits sensitive fields
     * (notably the password hash).
     *
     * @param user the entity to project
     * @return the profile DTO exposed over the API
     */
    private UserProfileResponse toProfileResponse(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getSberId(),
                user.getEmail(),
                user.getPhone(),
                user.getFirstName(),
                user.getLastName(),
                user.getKycStatus(),
                user.getRole(),
                user.getCreatedAt(),
                user.getLastLoginAt()
        );
    }
}
