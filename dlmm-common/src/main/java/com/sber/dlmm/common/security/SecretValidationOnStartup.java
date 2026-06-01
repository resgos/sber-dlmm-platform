package com.sber.dlmm.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.nio.charset.StandardCharsets;

/**
 * Runtime fail-fast for JWT/DB secrets (TD-2).
 *
 * <p>Compose-level {@code ${JWT_SECRET:?required}} already prevents start
 * when the variable is unset. This runner closes the next gap: someone
 * could still set {@code JWT_SECRET} to a dev-default placeholder
 * ({@code change-me-...}, the literal from {@code .env.example}, etc.)
 * and ship that to a prod cluster. In {@code prod} profile we refuse to
 * start in that case; in any non-prod profile we log a loud WARN and
 * continue so local dev keeps working out of the box.
 *
 * <p>Auto-registered via {@link DlmmSecretValidationAutoConfiguration}
 * so every service that depends on {@code dlmm-common} picks it up
 * without per-service wiring. Services that don't carry a
 * {@code dlmm.jwt.secret} property at all (e.g. {@code dlmm-price-oracle},
 * which has no jjwt on its classpath) skip the check via the
 * {@link Value} default — the property resolves to an empty string,
 * which we treat as "no JWT in use" rather than "dev-default" if no
 * services consume it.
 *
 * <p>Error messages point operators at
 * {@code docs/OPS-SECRETS-RUNBOOK.md} so the remediation is one click
 * away rather than a grep through the codebase.
 *
 * @see DevDefaultSecrets
 * @see DlmmSecretValidationAutoConfiguration
 */
public class SecretValidationOnStartup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecretValidationOnStartup.class);

    /** Profile name that triggers fail-fast (matches {@code spring.profiles.active=prod}). */
    static final String PROD_PROFILE = "prod";

    /** Link operators land on when the validator throws — keep in sync with the docs file path. */
    private static final String RUNBOOK_REF = "docs/OPS-SECRETS-RUNBOOK.md";

    private final String jwtSecret;
    private final Environment environment;

    /**
     * @param jwtSecret the configured {@code dlmm.jwt.secret}; empty when the
     *                 service declares no JWT secret at all
     * @param environment used to detect the {@code prod} profile, which switches
     *                   the validator from warn-and-continue to fail-fast
     */
    public SecretValidationOnStartup(
            @Value("${dlmm.jwt.secret:}") String jwtSecret,
            Environment environment) {
        this.jwtSecret = jwtSecret;
        this.environment = environment;
    }

    /**
     * {@link ApplicationRunner} entry point — runs once after the context is
     * ready and delegates to {@link #validateJwtSecret()}.
     *
     * @param args the application arguments (unused)
     */
    @Override
    public void run(ApplicationArguments args) {
        validateJwtSecret();
    }

    /**
     * Validates {@code dlmm.jwt.secret} and decides whether to abort startup.
     *
     * <p>Outcomes, in order:
     * <ul>
     *   <li>empty secret + non-prod ⇒ skip silently (service doesn't use JWT);</li>
     *   <li>a documented dev-default placeholder ⇒ fail in {@code prod}, WARN
     *       elsewhere — a leaked {@code .env.example} secret could otherwise be
     *       used to forge tokens;</li>
     *   <li>shorter than {@link DevDefaultSecrets#MIN_SECRET_BYTES} ⇒ fail in
     *       {@code prod}, WARN elsewhere (HS* would reject it at first use);</li>
     *   <li>otherwise ⇒ log an INFO confirming validation.</li>
     * </ul>
     * Fail-fast is delegated to {@link #failOrWarn(boolean, String, String)}.
     */
    private void validateJwtSecret() {
        boolean prod = environment.acceptsProfiles(Profiles.of(PROD_PROFILE));

        // Services that don't use JWT at all (e.g. price-oracle) leave
        // dlmm.jwt.secret unset entirely. Skip silently rather than
        // forcing every service to define the property.
        if ((jwtSecret == null || jwtSecret.isEmpty()) && !prod) {
            log.debug("SecretValidationOnStartup: no dlmm.jwt.secret configured; skipping (non-prod profile)");
            return;
        }

        int byteLength = jwtSecret == null ? 0 : jwtSecret.getBytes(StandardCharsets.UTF_8).length;
        String activeProfiles = String.join(",", environment.getActiveProfiles());

        if (DevDefaultSecrets.isDevDefault(jwtSecret)) {
            failOrWarn(prod,
                    String.format(
                            "REFUSING TO START: dlmm.jwt.secret is set to a documented dev-default value while "
                                    + "running with the '%s' profile active (active=[%s]). "
                                    + "This means JWT tokens could be forged by anyone who has read docker/.env.example. "
                                    + "Provide a real production secret (>= %d bytes, generated via 'openssl rand -base64 48') "
                                    + "via JWT_SECRET env var, Vault, or AWS Secrets Manager. "
                                    + "See %s for the full procedure.",
                            PROD_PROFILE, activeProfiles, DevDefaultSecrets.MIN_SECRET_BYTES, RUNBOOK_REF),
                    String.format(
                            "SecretValidationOnStartup: dlmm.jwt.secret is a DEV-DEFAULT placeholder "
                                    + "(active=[%s]); this is fine for local dev but MUST be overridden before prod. "
                                    + "See %s.",
                            activeProfiles, RUNBOOK_REF));
            return;
        }

        if (byteLength < DevDefaultSecrets.MIN_SECRET_BYTES) {
            failOrWarn(prod,
                    String.format(
                            "REFUSING TO START: dlmm.jwt.secret is only %d bytes; HS256 requires >= %d bytes "
                                    + "per RFC 7518 (active=[%s]). Regenerate via 'openssl rand -base64 48'. "
                                    + "See %s.",
                            byteLength, DevDefaultSecrets.MIN_SECRET_BYTES, activeProfiles, RUNBOOK_REF),
                    String.format(
                            "SecretValidationOnStartup: dlmm.jwt.secret is only %d bytes (HS256 needs >= %d); "
                                    + "service may fail at first JWT op. active=[%s]. See %s.",
                            byteLength, DevDefaultSecrets.MIN_SECRET_BYTES, activeProfiles, RUNBOOK_REF));
            return;
        }

        log.info("SecretValidationOnStartup: dlmm.jwt.secret validated ({} bytes, profile={})",
                byteLength, prod ? PROD_PROFILE : "non-prod");
    }

    /**
     * In prod, throws an {@link IllegalStateException} with {@code prodMsg}.
     * Otherwise logs {@code warnMsg} at WARN and returns.
     *
     * @param prod whether the {@code prod} profile is active (⇒ fail-fast)
     * @param prodMsg the exception message used when {@code prod} is {@code true}
     * @param warnMsg the message logged at WARN when {@code prod} is {@code false}
     * @throws IllegalStateException in {@code prod} to abort startup
     */
    private static void failOrWarn(boolean prod, String prodMsg, String warnMsg) {
        if (prod) {
            throw new IllegalStateException(prodMsg);
        }
        log.warn(warnMsg);
    }
}
