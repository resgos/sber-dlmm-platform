package com.sber.dlmm.fee.repository;

import com.sber.dlmm.fee.entity.AutoClaimPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository over {@link AutoClaimPolicy} rows (one per user).
 * The primary key is the {@code userId}, so {@link JpaRepository#findById} doubles
 * as "load this user's policy". Adds one finder the auto-claim scheduler uses to
 * enumerate the enabled policies each tick.
 */
@Repository
public interface AutoClaimPolicyRepository extends JpaRepository<AutoClaimPolicy, UUID> {

    /**
     * Used by the scheduler to walk every user with auto-claim turned
     * on. Tiny table (one row per user) — no pagination needed at
     * Sber's pilot scale.
     *
     * @return all policies with {@code enabled = true} (unordered)
     */
    List<AutoClaimPolicy> findByEnabledTrue();
}
