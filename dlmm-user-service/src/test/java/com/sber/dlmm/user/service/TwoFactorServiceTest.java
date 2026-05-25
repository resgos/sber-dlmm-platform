package com.sber.dlmm.user.service;

import com.sber.dlmm.user.entity.UserTwoFactor;
import com.sber.dlmm.user.repository.UserTwoFactorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.mockito.invocation.InvocationOnMock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * Sprint 11 G-20 — TOTP service tests.
 *
 * <p>Repository mocked via Mockito with an in-memory HashMap behind it —
 * gives us full lifecycle (findById/save/delete) without the boilerplate
 * of hand-implementing JpaRepository. Real DB integration is covered
 * by the smoke test in the PR body.
 */
@ExtendWith(MockitoExtension.class)
class TwoFactorServiceTest {

    @Mock private UserTwoFactorRepository repository;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private TwoFactorService service;

    /** HashMap-backed in-memory store wired via lenient stubs on the mock —
     *  keeps Mockito's verify() available while letting the test code use
     *  the service's real state-mutating paths. */
    private final Map<UUID, UserTwoFactor> store = new HashMap<>();

    @BeforeEach
    void setUp() {
        store.clear();
        lenient().when(repository.findById(any(UUID.class)))
                .thenAnswer((InvocationOnMock inv) -> Optional.ofNullable(store.get(inv.<UUID>getArgument(0))));
        lenient().when(repository.save(any(UserTwoFactor.class)))
                .thenAnswer((InvocationOnMock inv) -> {
                    UserTwoFactor row = inv.getArgument(0);
                    store.put(row.getUserId(), row);
                    return row;
                });
        lenient().doAnswer((InvocationOnMock inv) -> {
            UserTwoFactor row = inv.getArgument(0);
            store.remove(row.getUserId());
            return null;
        }).when(repository).delete(any(UserTwoFactor.class));
        service = new TwoFactorService(repository, encoder);
    }

    // ── Pure TOTP / base32 algorithm tests (no repo) ──────────────────────

    @Test
    @DisplayName("base32 round-trip — encode then decode is identity")
    void base32RoundTrip() {
        byte[] data = "Hello World!".getBytes();
        String encoded = TwoFactorService.base32Encode(data);
        byte[] decoded = TwoFactorService.base32Decode(encoded);
        assertEquals("Hello World!", new String(decoded));
    }

    @Test
    @DisplayName("RFC 6238 §B test vector — '12345678901234567890' at T=59 → 287082")
    void rfc6238TestVector() {
        // RFC 6238 Appendix B SHA1 vector: at T=59 the truncated value is
        // 94287082; mod 10^6 = 287082 (the 6-digit display value).
        byte[] key = "12345678901234567890".getBytes();
        long step = 59L / 30L;
        assertEquals(287082, TwoFactorService.generateCodeForStep(key, step),
                "RFC 6238 vector mismatch — TOTP impl is broken");
    }

    @Test
    @DisplayName("verifyTotp accepts a self-generated code at current time")
    void verifyTotpSelfRoundTrip() {
        // Round-trip the generator/verifier against each other so we
        // don't depend on external vectors for the happy path.
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        assertTrue(service.verifyTotp("JBSWY3DPEHPK3PXP", codeFor("JBSWY3DPEHPK3PXP", now, 0), now));
    }

    @Test
    @DisplayName("verifyTotp accepts the code from the previous step (±1 tolerance)")
    void verifyTotpToleratesPreviousStep() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        assertTrue(service.verifyTotp("JBSWY3DPEHPK3PXP", codeFor("JBSWY3DPEHPK3PXP", now, -1), now),
                "±1 step tolerance must accept the previous 30s window's code");
    }

    @Test
    @DisplayName("verifyTotp rejects a code from 5 steps ago (outside tolerance)")
    void verifyTotpRejectsExpired() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        assertFalse(service.verifyTotp("JBSWY3DPEHPK3PXP", codeFor("JBSWY3DPEHPK3PXP", now, -5), now),
                "Code more than ±1 step away must be rejected");
    }

    @Test
    @DisplayName("verifyTotp rejects non-6-digit input")
    void verifyTotpRejectsMalformed() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        assertFalse(service.verifyTotp("JBSWY3DPEHPK3PXP", "12345", now));
        assertFalse(service.verifyTotp("JBSWY3DPEHPK3PXP", "1234567", now));
        assertFalse(service.verifyTotp("JBSWY3DPEHPK3PXP", "abcdef", now));
        assertFalse(service.verifyTotp("JBSWY3DPEHPK3PXP", null, now));
    }

    @Test
    @DisplayName("buildOtpauthUri includes algorithm, digits, period, secret, and issuer")
    void otpauthUriShape() {
        String uri = TwoFactorService.buildOtpauthUri("demo@sber.ru", "JBSWY3DPEHPK3PXP");
        assertTrue(uri.startsWith("otpauth://totp/"));
        assertTrue(uri.contains("secret=JBSWY3DPEHPK3PXP"));
        assertTrue(uri.contains("algorithm=SHA1"));
        assertTrue(uri.contains("digits=6"));
        assertTrue(uri.contains("period=30"));
        assertTrue(uri.contains("issuer="));
    }

    // ── Service-level lifecycle tests ─────────────────────────────────────

    @Test
    @DisplayName("beginSetup returns secret + 10 codes WITHOUT persisting")
    void beginSetupNoPersist() {
        UUID userId = UUID.randomUUID();
        TwoFactorService.SetupChallenge challenge = service.beginSetup(userId, "demo@sber.ru");
        assertNotNull(challenge.secret());
        assertEquals(10, challenge.recoveryCodes().size());
        assertTrue(challenge.otpauthUri().contains("secret=" + challenge.secret()));
        assertTrue(store.isEmpty(),
                "beginSetup must not persist — that happens at enable()");
    }

    @Test
    @DisplayName("enable: valid code → row persisted, enabled=true, codes hashed")
    void enableHappyPath() {
        UUID userId = UUID.randomUUID();
        TwoFactorService.SetupChallenge ch = enrol(userId);

        UserTwoFactor row = store.get(userId);
        assertNotNull(row, "Row must be persisted");
        assertTrue(row.isEnabled());
        assertEquals(ch.secret(), row.getSecret());
        assertNotNull(row.getEnabledAt());
        assertEquals(10, row.getRecoveryCodesHashed().length);
        for (int i = 0; i < ch.recoveryCodes().size(); i++) {
            assertFalse(row.getRecoveryCodesHashed()[i].equals(ch.recoveryCodes().get(i)),
                    "Recovery code must be hashed, not stored plaintext");
        }
    }

    @Test
    @DisplayName("enable: rejects bogus TOTP code with 400")
    void enableRejectsBadCode() {
        UUID userId = UUID.randomUUID();
        TwoFactorService.SetupChallenge ch = service.beginSetup(userId, "demo@sber.ru");

        TwoFactorException ex = assertThrows(TwoFactorException.class,
                () -> service.enable(userId, ch.secret(), ch.recoveryCodes(), "000000"));
        assertEquals("TWO_FACTOR_INVALID_CODE", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertTrue(store.isEmpty(), "Nothing should persist on rejection");
    }

    @Test
    @DisplayName("enable: 409 if already enabled (must disable first to re-enrol)")
    void enableRejectsWhenAlreadyEnabled() {
        UUID userId = UUID.randomUUID();
        enrol(userId);

        TwoFactorException ex = assertThrows(TwoFactorException.class,
                () -> service.beginSetup(userId, "demo@sber.ru"));
        assertEquals("TWO_FACTOR_ALREADY_ENABLED", ex.getErrorCode());
        assertEquals(409, ex.getHttpStatus());
    }

    @Test
    @DisplayName("verify: current TOTP accepted")
    void verifyTotpAccepted() {
        UUID userId = UUID.randomUUID();
        TwoFactorService.SetupChallenge ch = enrol(userId);
        assertTrue(service.verify(userId, currentTotpFor(ch.secret())));
    }

    @Test
    @DisplayName("verify: wrong code → throws TWO_FACTOR_VERIFY_FAILED (401)")
    void verifyWrongCodeRejected() {
        UUID userId = UUID.randomUUID();
        enrol(userId);

        TwoFactorException ex = assertThrows(TwoFactorException.class,
                () -> service.verify(userId, "000000"));
        assertEquals("TWO_FACTOR_VERIFY_FAILED", ex.getErrorCode());
        assertEquals(401, ex.getHttpStatus());
    }

    @Test
    @DisplayName("verify: recovery code accepted once, then invalidated")
    void recoveryCodeConsumedOnce() {
        UUID userId = UUID.randomUUID();
        TwoFactorService.SetupChallenge ch = enrol(userId);

        String firstCode = ch.recoveryCodes().get(0);
        assertTrue(service.verify(userId, firstCode), "First use of recovery code must succeed");

        UserTwoFactor row = store.get(userId);
        assertEquals(9, row.getRecoveryCodesHashed().length, "Used code removed from array");
        assertEquals(1, row.getRecoveryCodesUsed(), "Counter increments");

        assertThrows(TwoFactorException.class, () -> service.verify(userId, firstCode),
                "Second use of the same code must fail");
    }

    @Test
    @DisplayName("verify: recovery code accepted with dashes stripped")
    void recoveryCodeAcceptedWithoutDashes() {
        UUID userId = UUID.randomUUID();
        TwoFactorService.SetupChallenge ch = enrol(userId);

        String stripped = ch.recoveryCodes().get(0).replace("-", "");
        assertTrue(service.verify(userId, stripped),
                "Recovery code verification must normalise away dashes");
    }

    @Test
    @DisplayName("disable: wipes the row, status returns enabled=false")
    void disableResetsState() {
        UUID userId = UUID.randomUUID();
        enrol(userId);
        assertTrue(service.status(userId).enabled());

        service.disable(userId);
        TwoFactorService.StatusView status = service.status(userId);
        assertFalse(status.enabled());
        assertNull(status.enabledAt());
        assertEquals(0, status.recoveryCodesRemaining());
        assertFalse(store.containsKey(userId), "Row gone — re-enable rotates secret");
    }

    @Test
    @DisplayName("disable on non-enrolled user: idempotent no-op")
    void disableIdempotent() {
        UUID userId = UUID.randomUUID();
        service.disable(userId); // must not throw
        assertFalse(service.status(userId).enabled());
    }

    @Test
    @DisplayName("verify on non-enrolled user: 409 TWO_FACTOR_NOT_ENABLED")
    void verifyOnUnenrolled() {
        TwoFactorException ex = assertThrows(TwoFactorException.class,
                () -> service.verify(UUID.randomUUID(), "123456"));
        assertEquals("TWO_FACTOR_NOT_ENABLED", ex.getErrorCode());
        assertEquals(409, ex.getHttpStatus());
    }

    @Test
    @DisplayName("status returns recovery codes remaining as the array length, not the counter")
    void statusReflectsArrayLength() {
        UUID userId = UUID.randomUUID();
        TwoFactorService.SetupChallenge ch = enrol(userId);
        assertEquals(10, service.status(userId).recoveryCodesRemaining());

        service.verify(userId, ch.recoveryCodes().get(0));
        service.verify(userId, ch.recoveryCodes().get(1));
        assertEquals(8, service.status(userId).recoveryCodesRemaining(),
                "remaining must reflect un-consumed codes, not the total minus a counter");
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Run the begin → enable happy path and return the challenge so the
     *  test can subsequently exercise verify/disable using the same secret
     *  and recovery codes. Most tests want this combined step. */
    private TwoFactorService.SetupChallenge enrol(UUID userId) {
        TwoFactorService.SetupChallenge ch = service.beginSetup(userId, "demo@sber.ru");
        service.enable(userId, ch.secret(), ch.recoveryCodes(), currentTotpFor(ch.secret()));
        return ch;
    }

    private static String currentTotpFor(String base32Secret) {
        return codeFor(base32Secret, Instant.now(), 0);
    }

    /** Compute the TOTP code that would be valid at {@code at + stepOffset*30s}.
     *  Mirrors the service's internal computation; lets tests drive happy
     *  paths and ±N-step tolerance cases without depending on wall clock. */
    private static String codeFor(String base32Secret, Instant at, long stepOffset) {
        byte[] key = TwoFactorService.base32Decode(base32Secret);
        long step = (at.getEpochSecond() / 30L) + stepOffset;
        return String.format("%06d", TwoFactorService.generateCodeForStep(key, step));
    }
}
