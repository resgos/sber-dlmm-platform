package com.sber.dlmm.token.repository;

import com.sber.dlmm.common.enums.TokenType;
import com.sber.dlmm.token.entity.Token;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TokenRepository extends JpaRepository<Token, UUID> {

    Optional<Token> findBySymbol(String symbol);

    boolean existsBySymbol(String symbol);

    List<Token> findByTokenType(TokenType type);

    List<Token> findByActiveTrue();

    Page<Token> findAll(Pageable pageable);
}
