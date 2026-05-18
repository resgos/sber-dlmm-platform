package com.sber.dlmm.token.repository;

import com.sber.dlmm.token.entity.SpasiboOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SpasiboOperationRepository extends JpaRepository<SpasiboOperation, UUID> {

    Optional<SpasiboOperation> findByReference(String reference);
}
