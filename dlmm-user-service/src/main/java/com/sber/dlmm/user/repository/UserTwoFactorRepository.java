package com.sber.dlmm.user.repository;

import com.sber.dlmm.user.entity.UserTwoFactor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Sprint 11 G-20 — 2FA TOTP per-user state.
 *
 * <p>One row per user (PK = userId). Lookup is always by userId; no
 * search / pagination needed.
 */
@Repository
public interface UserTwoFactorRepository extends JpaRepository<UserTwoFactor, UUID> {
}
