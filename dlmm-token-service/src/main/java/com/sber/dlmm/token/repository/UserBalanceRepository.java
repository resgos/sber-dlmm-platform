package com.sber.dlmm.token.repository;

import com.sber.dlmm.token.entity.UserBalance;
import com.sber.dlmm.token.entity.UserBalanceId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserBalanceRepository extends JpaRepository<UserBalance, UserBalanceId> {

    List<UserBalance> findByUserId(UUID userId);

    Optional<UserBalance> findByUserIdAndTokenId(UUID userId, UUID tokenId);

    List<UserBalance> findByTokenId(UUID tokenId);

    @Modifying
    @Query("UPDATE UserBalance b SET b.available = b.available - :amount, " +
           "b.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE b.userId = :userId AND b.tokenId = :tokenId " +
           "AND b.available >= :amount")
    int deductAvailable(@Param("userId") UUID userId,
                        @Param("tokenId") UUID tokenId,
                        @Param("amount") long amount);

    @Modifying
    @Query("UPDATE UserBalance b SET b.available = b.available + :amount, " +
           "b.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE b.userId = :userId AND b.tokenId = :tokenId")
    int creditAvailable(@Param("userId") UUID userId,
                        @Param("tokenId") UUID tokenId,
                        @Param("amount") long amount);

    @Modifying
    @Query("UPDATE UserBalance b SET b.available = b.available - :amount, " +
           "b.locked = b.locked + :amount, b.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE b.userId = :userId AND b.tokenId = :tokenId " +
           "AND b.available >= :amount")
    int lockBalance(@Param("userId") UUID userId,
                    @Param("tokenId") UUID tokenId,
                    @Param("amount") long amount);

    @Modifying
    @Query("UPDATE UserBalance b SET b.locked = b.locked - :amount, " +
           "b.available = b.available + :amount, b.updatedAt = CURRENT_TIMESTAMP " +
           "WHERE b.userId = :userId AND b.tokenId = :tokenId " +
           "AND b.locked >= :amount")
    int unlockBalance(@Param("userId") UUID userId,
                      @Param("tokenId") UUID tokenId,
                      @Param("amount") long amount);
}
