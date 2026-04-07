package com.sber.dlmm.token.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class UserBalanceId implements Serializable {

    private UUID userId;
    private UUID tokenId;

    public UserBalanceId() {
    }

    public UserBalanceId(UUID userId, UUID tokenId) {
        this.userId = userId;
        this.tokenId = tokenId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public UUID getTokenId() {
        return tokenId;
    }

    public void setTokenId(UUID tokenId) {
        this.tokenId = tokenId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserBalanceId that = (UserBalanceId) o;
        return Objects.equals(userId, that.userId) && Objects.equals(tokenId, that.tokenId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, tokenId);
    }
}
