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

/**
 * Spring Data repository for the {@link Token} catalog aggregate.
 *
 * <p>Provides lookup by primary key (inherited from {@link JpaRepository})
 * plus the symbol-based and type/active filters the token-service uses to
 * resolve and list tokens. All derived (method-name) queries — no JPQL here.
 */
@Repository
public interface TokenRepository extends JpaRepository<Token, UUID> {

    /**
     * Looks up a token by its unique trading symbol.
     *
     * @param symbol the token symbol (matches the DB-unique {@code symbol} column)
     * @return the matching token, or empty if no token has that symbol
     */
    Optional<Token> findBySymbol(String symbol);

    /**
     * Existence check by symbol — used to reject duplicate symbols on token
     * creation without loading the row.
     *
     * @param symbol the token symbol to test
     * @return true iff a token with that symbol already exists
     */
    boolean existsBySymbol(String symbol);

    /**
     * Lists all tokens of a given classification (active and inactive alike),
     * in no guaranteed order.
     *
     * @param type the {@link TokenType} to filter by
     * @return all tokens of that type (possibly empty)
     */
    List<Token> findByTokenType(TokenType type);

    /**
     * Lists every currently-active token ({@code active = true}), in no
     * guaranteed order.
     *
     * @return the active tokens (possibly empty)
     */
    List<Token> findByActiveTrue();

    /**
     * Returns one page of the full token catalog for the paginated listing
     * endpoint; sort order is taken from the {@link Pageable}.
     *
     * @param pageable page index, size and sort
     * @return the requested page of tokens
     */
    Page<Token> findAll(Pageable pageable);
}
