package com.sber.dlmm.fee.repository;

import com.sber.dlmm.fee.entity.AutoClaimPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AutoClaimPolicyRepository extends JpaRepository<AutoClaimPolicy, UUID> {

    /**
     * Used by the scheduler to walk every user with auto-claim turned
     * on. Tiny table (one row per user) — no pagination needed at
     * Sber's pilot scale.
     */
    List<AutoClaimPolicy> findByEnabledTrue();
}
