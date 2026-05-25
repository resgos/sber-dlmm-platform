package com.sber.dlmm.user.repository;

import com.sber.dlmm.user.entity.Org;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OrgRepository extends JpaRepository<Org, UUID> {

    /** Orgs created by this user (one-to-many during account life;
     *  almost always a single row in practice). */
    List<Org> findByOwnerId(UUID ownerId);
}
