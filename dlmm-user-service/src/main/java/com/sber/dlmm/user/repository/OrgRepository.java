package com.sber.dlmm.user.repository;

import com.sber.dlmm.user.entity.Org;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Org} organisations (Sprint 11 G-21).
 *
 * <p>Inherits CRUD from {@link JpaRepository}; adds a lookup by creator.
 */
@Repository
public interface OrgRepository extends JpaRepository<Org, UUID> {

    /** Orgs created by this user (one-to-many during account life;
     *  almost always a single row in practice).
     *
     * @param ownerId the creator's user id ({@link Org#getOwnerId()})
     * @return orgs created by this user; empty if none */
    List<Org> findByOwnerId(UUID ownerId);
}
