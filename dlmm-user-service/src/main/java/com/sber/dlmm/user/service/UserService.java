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

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        User user = userRepository.findByEmail(req.email())
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        String accessToken = issueAccessToken(user);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        return new AuthResponse(
                accessToken,
                refreshToken,
                jwtTokenProvider.getAccessTokenExpirySeconds(),
                toProfileResponse(user)
        );
    }

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

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));
        return toProfileResponse(user);
    }

    /** Used by pool-engine for KYC pre-checks before swap / add-liquidity. */
    @Transactional(readOnly = true)
    public boolean isKycVerified(UUID userId) {
        return userRepository.findById(userId)
                .map(u -> u.getKycStatus() == com.sber.dlmm.common.enums.KycStatus.VERIFIED)
                .orElse(false);
    }

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

    @Transactional
    public UserProfileResponse updateRole(UUID userId, UpdateRoleRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));

        user.setRole(req.role());
        user = userRepository.save(user);
        log.info("User role updated: id={}, role={}", user.getId(), req.role());
        return toProfileResponse(user);
    }

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

    @Transactional
    public void unblockUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));

        user.setKycStatus(KycStatus.PENDING);
        userRepository.save(user);
        log.info("User unblocked: id={}", userId);
    }

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
                user.getCreatedAt()
        );
    }
}
