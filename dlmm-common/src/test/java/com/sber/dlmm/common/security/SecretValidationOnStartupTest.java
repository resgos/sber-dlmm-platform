package com.sber.dlmm.common.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the contract of {@link SecretValidationOnStartup}:
 * <ul>
 *   <li>dev-default secret + non-prod profile → WARN, doesn't throw</li>
 *   <li>dev-default secret + prod profile → IllegalStateException with
 *       remediation pointing at the runbook</li>
 *   <li>real secret (≥32 bytes, not in dev-default list) + prod profile
 *       → starts cleanly, logs INFO</li>
 *   <li>empty secret + prod profile → throws (counts as dev-default)</li>
 *   <li>secret &lt; 32 bytes + prod → throws; + non-prod → WARN</li>
 *   <li>change-me-* family is detected via the prefix rule</li>
 * </ul>
 */
class SecretValidationOnStartupTest {

    private static final String REAL_PROD_SECRET =
            "QzVk7sGv2nA9bLpW8fH4mY6cR3xT0eU1iD8jZ2qH5oS=real-prod-grade";
    private static final String SHIPPED_DEV_DEFAULT =
            "super-secret-jwt-key-for-dlmm-platform-256-bit-min-dev-only";
    private static final String CHANGE_ME_VARIANT =
            "change-me-in-production-please-use-a-real-secret";

    private ListAppender<ILoggingEvent> appender;
    private Logger validatorLogger;

    @BeforeEach
    void setUp() {
        validatorLogger = (Logger) LoggerFactory.getLogger(SecretValidationOnStartup.class);
        appender = new ListAppender<>();
        appender.start();
        validatorLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        validatorLogger.detachAppender(appender);
    }

    // ── dev-default in dev profile ────────────────────────────────────

    @Test
    void devDefaultSecret_inDevProfile_warnsButDoesNotThrow() {
        SecretValidationOnStartup runner = runner(SHIPPED_DEV_DEFAULT, "dev");

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
        assertThat(warnMessages()).anyMatch(m -> m.contains("DEV-DEFAULT placeholder"));
    }

    @Test
    void changeMeVariant_inDevProfile_warnsButDoesNotThrow() {
        SecretValidationOnStartup runner = runner(CHANGE_ME_VARIANT, "dev");

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
        assertThat(warnMessages()).anyMatch(m -> m.contains("DEV-DEFAULT placeholder"));
    }

    // ── dev-default in prod profile ───────────────────────────────────

    @Test
    void devDefaultSecret_inProdProfile_throwsWithRemediationMessage() {
        SecretValidationOnStartup runner = runner(SHIPPED_DEV_DEFAULT, "prod");

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REFUSING TO START")
                .hasMessageContaining("dev-default")
                .hasMessageContaining("OPS-SECRETS-RUNBOOK.md")
                .hasMessageContaining("openssl rand");
    }

    @Test
    void changeMeVariant_inProdProfile_throws() {
        SecretValidationOnStartup runner = runner(CHANGE_ME_VARIANT, "prod");

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REFUSING TO START");
    }

    // ── real secret in prod profile ───────────────────────────────────

    @Test
    void realSecret_inProdProfile_startsCleanlyAndLogsInfo() {
        SecretValidationOnStartup runner = runner(REAL_PROD_SECRET, "prod");

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
        assertThat(infoMessages()).anyMatch(m -> m.contains("validated"));
        assertThat(warnMessages()).isEmpty();
    }

    @Test
    void realSecret_inProdProfile_withMultipleProfiles_stillPasses() {
        SecretValidationOnStartup runner = runner(REAL_PROD_SECRET, "prod", "vault");

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
    }

    // ── empty secret ──────────────────────────────────────────────────

    @Test
    void emptySecret_inProdProfile_throws() {
        SecretValidationOnStartup runner = runner("", "prod");

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REFUSING TO START");
    }

    @Test
    void emptySecret_inDevProfile_isSkippedSilently() {
        // Services that don't use JWT (price-oracle) leave the property
        // unset entirely. In non-prod that's a valid "I don't use JWT"
        // signal — don't pester the operator with a warning.
        SecretValidationOnStartup runner = runner("", "dev");

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
        assertThat(warnMessages()).isEmpty();
    }

    // ── short secret ──────────────────────────────────────────────────

    @Test
    void shortSecret_inProdProfile_throws() {
        SecretValidationOnStartup runner = runner("only-16-bytes-ok", "prod");

        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REFUSING TO START")
                .hasMessageContaining("HS256")
                .hasMessageContaining("RFC 7518");
    }

    @Test
    void shortSecret_inDevProfile_warnsButDoesNotThrow() {
        SecretValidationOnStartup runner = runner("only-16-bytes-ok", "dev");

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();
        assertThat(warnMessages()).anyMatch(m -> m.contains("HS256"));
    }

    // ── helpers ───────────────────────────────────────────────────────

    private SecretValidationOnStartup runner(String secret, String... activeProfiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(activeProfiles);
        return new SecretValidationOnStartup(secret, env);
    }

    private java.util.List<String> warnMessages() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private java.util.List<String> infoMessages() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
