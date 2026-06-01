package com.sber.dlmm.user.repository;

import com.sber.dlmm.user.entity.UserSelfRestriction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link UserSelfRestriction} history rows
 * (Sprint 6 #6.7, self-imposed product restrictions per 115-ФЗ).
 *
 * <p>The table is append-only, so reads return the full event history and the
 * service layer derives the current state from it. Inherits CRUD from
 * {@link JpaRepository}.
 */
@Repository
public interface UserSelfRestrictionRepository extends JpaRepository<UserSelfRestriction, UUID> {

    /**
     * Sprint 6 #6.7 — full chronological history for a user. Service-layer
     * computes whether restriction is currently active (latest SET without
     * a subsequent past-effective LIFTED).
     *
     * @param userId the user whose restriction history to load
     * @return all of the user's restriction rows, oldest first (ascending
     *         {@code createdAt}); empty if the user never set one
     */
    List<UserSelfRestriction> findByUserIdOrderByCreatedAtAsc(UUID userId);
}
