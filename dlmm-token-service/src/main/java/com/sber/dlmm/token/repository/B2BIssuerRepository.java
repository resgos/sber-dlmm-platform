package com.sber.dlmm.token.repository;

import com.sber.dlmm.token.entity.B2BIssuer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface B2BIssuerRepository extends JpaRepository<B2BIssuer, UUID> {

    Optional<B2BIssuer> findByInn(String inn);

    List<B2BIssuer> findByKybStatus(B2BIssuer.KybStatus status);
}
