package com.sber.dlmm.user.repository;

import com.sber.dlmm.user.entity.UserSelfRestriction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface UserSelfRestrictionRepository extends JpaRepository<UserSelfRestriction, UUID> {

    /**
     * Sprint 6 #6.7 — full chronological history for a user. Service-layer
     * computes whether restriction is currently active (latest SET without
     * a subsequent past-effective LIFTED).
     */
    List<UserSelfRestriction> findByUserIdOrderByCreatedAtAsc(UUID userId);
}
