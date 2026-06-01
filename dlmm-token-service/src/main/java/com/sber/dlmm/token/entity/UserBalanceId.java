package com.sber.dlmm.token.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary-key class for {@link UserBalance} (the {@code @IdClass}).
 *
 * <p>A user's balance is keyed by the pair {@code (userId, tokenId)} — one row
 * per user per token. JPA requires this key type to be {@link Serializable},
 * to expose a no-arg constructor, and to implement {@link #equals(Object)} /
 * {@link #hashCode()} over exactly the key fields (done below) so the
 * persistence context can de-duplicate and look up managed entities by id.
 * The field names and types here must mirror the {@code @Id} fields on
 * {@link UserBalance}.
 */
public class UserBalanceId implements Serializable {

    private UUID userId;
    private UUID tokenId;

    /** No-arg constructor required by JPA to instantiate the id class. */
    public UserBalanceId() {
    }

    /**
     * Convenience constructor for building a key to look up a balance.
     *
     * @param userId  owning user's id
     * @param tokenId token catalog id
     */
    public UserBalanceId(UUID userId, UUID tokenId) {
        this.userId = userId;
        this.tokenId = tokenId;
    }

    /** @return the owning user's id (first key component). */
    public UUID getUserId() {
        return userId;
    }

    /** @param userId the owning user's id to set. */
    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    /** @return the token catalog id (second key component). */
    public UUID getTokenId() {
        return tokenId;
    }

    /** @param tokenId the token catalog id to set. */
    public void setTokenId(UUID tokenId) {
        this.tokenId = tokenId;
    }

    /**
     * Value equality over both key components, as required for a JPA id class.
     *
     * @param o the other object
     * @return true iff {@code o} is a {@code UserBalanceId} with the same
     *         {@code userId} and {@code tokenId}
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserBalanceId that = (UserBalanceId) o;
        return Objects.equals(userId, that.userId) && Objects.equals(tokenId, that.tokenId);
    }

    /**
     * Hash consistent with {@link #equals(Object)}, derived from both key
     * components.
     *
     * @return the composite hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(userId, tokenId);
    }
}
