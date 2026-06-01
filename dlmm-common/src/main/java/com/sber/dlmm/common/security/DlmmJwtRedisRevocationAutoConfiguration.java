package com.sber.dlmm.common.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Sprint 8 AU-3 (extracted Sprint 9 day-3 to fix startup crash) —
 * Redis-backed {@link JwtRevocationService} auto-config.
 *
 * <p>Why split from {@link DlmmJwtAutoConfiguration}: the bean factory
 * method here references {@link StringRedisTemplate} in its parameter
 * type. Even with {@code @ConditionalOnClass(StringRedisTemplate.class)}
 * on the method, Spring needs to LOAD this @Configuration class to
 * introspect its methods — and on services without spring-data-redis
 * on the classpath, that introspection fails with
 * {@code NoClassDefFoundError: StringRedisTemplate}.
 *
 * <p>Putting the gate at the CLASS level via {@code @ConditionalOnClass}
 * + {@code @ConditionalOnBean} means Spring skips loading this class
 * entirely on services without Redis. The Noop fallback stays in
 * {@link DlmmJwtAutoConfiguration} so it always registers via
 * {@code @ConditionalOnMissingBean}.
 *
 * <p>Verified by fee-service restart after Sprint 8 (which had Redis
 * in classpath via Sprint 8 update) being reverted to no-Redis state
 * — fee-service depends on dlmm-common but does NOT need redis. Pre-
 * split: NoClassDefFoundError; post-split: silent skip + Noop registered.
 */
@AutoConfiguration
@ConditionalOnClass(StringRedisTemplate.class)
public class DlmmJwtRedisRevocationAutoConfiguration {

    /**
     * Registers the Redis-backed {@link JwtRevocationService}. Wins over the
     * no-op fallback in {@link DlmmJwtAutoConfiguration} because it is registered
     * first (the fallback is {@code @ConditionalOnMissingBean}). Guarded by
     * {@code @ConditionalOnBean(StringRedisTemplate.class)} so it self-skips when
     * Redis auto-config didn't produce a template.
     *
     * @param redis the auto-configured Redis template
     * @return the Redis-backed revocation denylist service
     */
    @Bean
    @ConditionalOnMissingBean(JwtRevocationService.class)
    @ConditionalOnBean(StringRedisTemplate.class)
    public JwtRevocationService dlmmRedisJwtRevocationService(StringRedisTemplate redis) {
        return new RedisJwtRevocationService(redis);
    }
}
