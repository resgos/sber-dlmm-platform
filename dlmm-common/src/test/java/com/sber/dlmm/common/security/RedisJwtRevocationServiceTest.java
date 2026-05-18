package com.sber.dlmm.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Pins {@link RedisJwtRevocationService} contract — the abstraction over
 * Sprint 8 AU-3 denylist. Uses mocked {@link StringRedisTemplate} so the
 * suite doesn't need an embedded Redis (matches the rest of dlmm-common,
 * which avoids Testcontainers per CLAUDE.md).
 */
class RedisJwtRevocationServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private RedisJwtRevocationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        service = new RedisJwtRevocationService(redis);
    }

    @Test
    void isRevoked_trueWhenKeyExists() {
        String jti = UUID.randomUUID().toString();
        when(redis.hasKey("dlmm:revoked-jti:" + jti)).thenReturn(true);

        assertThat(service.isRevoked(jti)).isTrue();
    }

    @Test
    void isRevoked_falseWhenKeyMissing() {
        String jti = UUID.randomUUID().toString();
        when(redis.hasKey("dlmm:revoked-jti:" + jti)).thenReturn(false);

        assertThat(service.isRevoked(jti)).isFalse();
    }

    @Test
    void isRevoked_falseForNullOrBlankJti_noRedisCall() {
        // Legacy tokens (pre-AU-3) have no jti — must not even touch Redis.
        assertThat(service.isRevoked(null)).isFalse();
        assertThat(service.isRevoked("")).isFalse();
        assertThat(service.isRevoked("  ")).isFalse();
        verifyNoInteractions(redis);
    }

    @Test
    void isRevoked_failsOpenOnRedisOutage() {
        // Critical Sprint 8 AU-3 design choice — if Redis is down we treat
        // tokens as NOT revoked. Locking everyone out on a Redis hiccup is
        // worse than briefly missing a logout.
        String jti = UUID.randomUUID().toString();
        when(redis.hasKey("dlmm:revoked-jti:" + jti))
                .thenThrow(new RuntimeException("Redis connection refused"));

        assertThat(service.isRevoked(jti)).isFalse();
    }

    @Test
    void revoke_writesKeyWithExactTtl() {
        String jti = UUID.randomUUID().toString();
        service.revoke(jti, 1800L);  // 30 minutes

        verify(ops).set("dlmm:revoked-jti:" + jti, "1", Duration.ofSeconds(1800L));
    }

    @Test
    void revoke_skipsWriteWhenTtlNonPositive() {
        // Token already expired — Redis would reject TTL ≤ 0 anyway, but
        // we short-circuit to avoid the round-trip.
        String jti = UUID.randomUUID().toString();
        service.revoke(jti, 0L);
        service.revoke(jti, -5L);

        verifyNoInteractions(ops);
    }

    @Test
    void revoke_skipsForBlankJti() {
        service.revoke(null, 1800L);
        service.revoke("", 1800L);
        service.revoke("   ", 1800L);

        verifyNoInteractions(ops);
    }

    @Test
    void revoke_failsLoudOnRedisOutage() {
        // Opposite of isRevoked — if we can't WRITE the denylist entry,
        // logout silently failed. Caller (user-service logout endpoint)
        // must see the exception so the failure isn't hidden.
        String jti = UUID.randomUUID().toString();
        doThrow(new RuntimeException("Redis write failed"))
                .when(ops).set(anyString(), anyString(), any(Duration.class));

        assertThatThrownBy(() -> service.revoke(jti, 1800L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to record JWT revocation");
    }
}
