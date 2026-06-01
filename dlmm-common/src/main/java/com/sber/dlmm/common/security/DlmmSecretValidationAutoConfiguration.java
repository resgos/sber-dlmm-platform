package com.sber.dlmm.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Auto-registers {@link SecretValidationOnStartup} for every service that
 * depends on {@code dlmm-common}.
 *
 * <p>Kept as its own class (rather than wedged into
 * {@link DlmmJwtAutoConfiguration}) so the validator runs even on
 * services that don't pull in jjwt — e.g. {@code dlmm-price-oracle}.
 * The validator only acts on services that actually declare
 * {@code dlmm.jwt.secret}; others see an empty value and skip silently.
 *
 * <p>No {@code @ConditionalOnProperty} gate: the whole point is to fire
 * on services that have a misconfigured secret AND ones that are
 * correctly configured (the latter logs an INFO line, useful for
 * verifying via {@code docker logs} during a deploy).
 */
@AutoConfiguration
public class DlmmSecretValidationAutoConfiguration {

    /**
     * Registers the startup secret validator. The {@code dlmm.jwt.secret:}
     * default (empty) means services that don't define the property still get a
     * bean — the validator then treats an empty value as "no JWT in use" and
     * skips, except in {@code prod} where a missing/weak secret aborts boot.
     *
     * @param jwtSecret the configured {@code dlmm.jwt.secret}, defaulting to ""
     * @param environment used to detect the active profile (prod ⇒ fail-fast)
     * @return the {@link SecretValidationOnStartup} runner that validates the
     *         secret at application startup
     */
    @Bean
    @ConditionalOnMissingBean
    public SecretValidationOnStartup dlmmSecretValidationOnStartup(
            @Value("${dlmm.jwt.secret:}") String jwtSecret,
            Environment environment) {
        return new SecretValidationOnStartup(jwtSecret, environment);
    }
}
