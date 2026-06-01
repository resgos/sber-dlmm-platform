package com.sber.dlmm.user.service;

import com.sber.dlmm.user.entity.UserTwoFactor;
import com.sber.dlmm.user.repository.UserTwoFactorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sprint 11 G-20 — 2FA TOTP service.
 *
 * <p>Replaces the frontend-only stub ({@code twoFactorStore.ts} accepted any
 * 6-digit code, secret in localStorage). Real verification uses RFC 6238
 * HMAC-SHA1 with a 30-second window and ±1 step tolerance — the standard
 * authenticator-app contract (Google Authenticator, Я.Ключ, Microsoft
 * Authenticator, etc.).
 *
 * <p>Recovery codes are bcrypt-hashed (same encoder as user passwords —
 * a 4-character recovery code has only ~20 bits of entropy and would be
 * brute-forceable against a fast hash). The plaintext is shown ONCE
 * during begin-setup and never persisted.
 *
 * <p>State machine:
 * <pre>
 *   (none) ──beginSetup──▶ (pending; nothing persisted yet)
 *                           │
 *                           ├─enable(valid code)──▶ ENABLED
 *                           │                        │
 *                           │                        ├─disable──▶ (none)
 *                           │                        └─verify──▶ pass/fail
 *                           └─enable(bad code)───▶ (none, 400)
 * </pre>
 *
 * <p>Notes:
 * <ul>
 *   <li>{@code beginSetup} is read-only on the DB — it generates and returns
 *       a secret + 10 recovery codes without writing. The client must echo
 *       them back on {@code enable} so we know the user actually saw the
 *       recovery codes before we commit.</li>
 *   <li>Recovery codes are normalised (uppercase, no whitespace/dashes)
 *       before hashing AND before verifying — users routinely paste them
 *       with or without the {@code -} separator.</li>
 *   <li>Disable wipes the row entirely. Re-enabling rotates the secret —
 *       intentional, prevents a stale authenticator entry from silently
 *       still working after a "reset".</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TwoFactorService {

    private static final String HMAC_ALGORITHM = "HmacSHA1";

    /** RFC 6238 step size — 30 seconds is the universal authenticator convention. */
    private static final int TIME_STEP_SECONDS = 30;

    /** Tolerance window — accept current step ± 1 step. Covers ~30s clock drift
     *  on either side, which is generous (authenticator apps rarely drift
     *  more than a couple of seconds) but catches the common case of the
     *  user typing the code as it's about to roll over. */
    private static final int TOLERANCE_STEPS = 1;

    /** RFC 6238 §5.3 — last 6 digits of dynamic-truncated HMAC. */
    private static final int CODE_DIGITS = 6;
    private static final int CODE_MODULO = 1_000_000;

    /** RFC 4648 base32 alphabet — the standard authenticator-app contract.
     *  Encode + decode + recovery-code generation all share this constant. */
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /** 20 bytes / 160 bits — RFC 4226 §4 recommended secret size for HMAC-SHA1. */
    private static final int SECRET_BYTE_LENGTH = 20;

    private static final int RECOVERY_CODE_COUNT = 10;
    /** 8 chars displayed as XXXX-XXXX. ~40 bits of entropy * bcrypt = enough. */
    private static final int RECOVERY_CODE_CHARS = 8;

    private final UserTwoFactorRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    // ── DTO records — used by controller layer ─────────────────────────────

    /**
     * Result of {@link #beginSetup}: the data the client needs to render the
     * enrolment UI. Nothing here is persisted yet — the client must echo
     * {@code secret} and {@code recoveryCodes} back to {@link #enable}. The
     * plaintext recovery codes are shown to the user exactly once, here.
     *
     * @param secret        the freshly generated base32 TOTP secret
     * @param otpauthUri    the {@code otpauth://} URI to encode as a QR code
     * @param recoveryCodes the 10 one-time recovery codes in display form (XXXX-XXXX)
     */
    public record SetupChallenge(String secret, String otpauthUri, List<String> recoveryCodes) {}

    /**
     * Read-only view of a user's 2FA state returned by {@link #status}.
     *
     * @param enabled                 whether 2FA is currently enabled for the user
     * @param enabledAt               when 2FA was enabled, or null if not enabled
     * @param recoveryCodesRemaining  how many unused recovery codes are left (0 when disabled)
     */
    public record StatusView(boolean enabled, LocalDateTime enabledAt, int recoveryCodesRemaining) {}

    // ── Begin setup ────────────────────────────────────────────────────────

    /**
     * Generate a fresh secret + recovery codes WITHOUT persisting. The
     * client renders the QR / secret / codes UX; persistence happens in
     * {@link #enable} after the user types a valid TOTP code (proving
     * the authenticator app has the secret loaded correctly).
     *
     * <p>If the user is already enrolled, this throws — they should disable
     * first. Otherwise an interrupted re-enrol would overwrite the secret
     * on the next enable() call, silently invalidating the still-valid
     * authenticator entry.
     *
     * @param userId       the user beginning enrolment
     * @param accountLabel label shown in the authenticator app (typically the user's email)
     * @return the setup challenge (secret, otpauth URI, plaintext recovery codes)
     * @throws TwoFactorException 409 ({@link TwoFactorException#alreadyEnabled}) if 2FA is already enabled
     */
    @Transactional(readOnly = true)
    public SetupChallenge beginSetup(UUID userId, String accountLabel) {
        repository.findById(userId).ifPresent(existing -> {
            if (existing.isEnabled()) {
                throw TwoFactorException.alreadyEnabled();
            }
        });
        String secret = generateSecret();
        List<String> recoveryCodes = generateRecoveryCodes();
        String otpauth = buildOtpauthUri(accountLabel, secret);
        log.info("2FA begin-setup issued for user={}", userId);
        return new SetupChallenge(secret, otpauth, recoveryCodes);
    }

    // ── Enable (commit) ───────────────────────────────────────────────────

    /**
     * Persist 2FA enrolment after the user proves they have the
     * authenticator entry by typing a valid TOTP code derived from
     * the same secret returned by {@link #beginSetup}.
     *
     * <p>Recovery codes are bcrypt-hashed before storage; plaintext is
     * never persisted. Order is preserved so the UI can show
     * "code #3 used" provenance.
     *
     * @param userId        the user being enrolled
     * @param secret        the base32 secret echoed back from {@link #beginSetup}
     * @param recoveryCodes the exactly-{@value #RECOVERY_CODE_COUNT} recovery codes echoed back from begin
     * @param code          a 6-digit TOTP the user read from their app, proving the secret is loaded
     * @throws TwoFactorException 400 if the secret/recovery-code payload is malformed or the TOTP code is invalid;
     *                            409 if 2FA is already enabled
     */
    @Transactional
    public void enable(UUID userId, String secret, List<String> recoveryCodes, String code) {
        if (secret == null || secret.isBlank()) {
            throw TwoFactorException.invalidSetupPayload("secret is required");
        }
        if (recoveryCodes == null || recoveryCodes.size() != RECOVERY_CODE_COUNT) {
            throw TwoFactorException.invalidSetupPayload(
                    "expected " + RECOVERY_CODE_COUNT + " recovery codes");
        }
        if (code == null || !verifyTotp(secret, code, Instant.now())) {
            throw TwoFactorException.invalidEnableCode();
        }
        UserTwoFactor existing = repository.findById(userId).orElse(null);
        if (existing != null && existing.isEnabled()) {
            throw TwoFactorException.alreadyEnabled();
        }

        String[] hashed = recoveryCodes.stream()
                .map(this::normaliseRecoveryCode)
                .map(passwordEncoder::encode)
                .toArray(String[]::new);

        UserTwoFactor row = (existing != null) ? existing : new UserTwoFactor();
        row.setUserId(userId);
        row.setSecret(secret);
        row.setEnabled(true);
        row.setEnabledAt(LocalDateTime.now());
        row.setRecoveryCodesHashed(hashed);
        row.setRecoveryCodesUsed(0);
        repository.save(row);
        log.info("2FA enabled for user={}", userId);
    }

    // ── Verify (login flow) ───────────────────────────────────────────────

    /**
     * Login-flow second-factor check. Returns true on success, throws on
     * failure (so callers can let {@code GlobalExceptionHandler} render
     * the 401). A 6-digit numeric input is treated as a TOTP code; any
     * other shape is treated as a recovery code attempt.
     *
     * <p>Recovery-code path consumes the code (removes the matching hash
     * from the array) so it can't be reused. Counter increments
     * monotonically for analytics.
     *
     * @param userId the user whose second factor is being checked
     * @param code   the submitted credential — a 6-digit TOTP or a recovery code
     * @return true on success (the method never returns false — failure throws)
     * @throws TwoFactorException 401 if neither TOTP nor recovery matches.
     *                            409 if user has no 2FA enrolment.
     */
    @Transactional
    public boolean verify(UUID userId, String code) {
        UserTwoFactor row = repository.findById(userId)
                .filter(UserTwoFactor::isEnabled)
                .orElseThrow(TwoFactorException::notEnabled);
        if (code == null) {
            throw TwoFactorException.verifyFailed();
        }
        if (code.matches("\\d{6}") && verifyTotp(row.getSecret(), code, Instant.now())) {
            return true;
        }
        // Fall through to recovery-code path — handles both formats
        // (with-dash and without) via normalisation.
        if (consumeRecoveryCode(row, code)) {
            return true;
        }
        log.warn("2FA verify failed for user={}", userId);
        throw TwoFactorException.verifyFailed();
    }

    /**
     * Attempts to match a submitted recovery code against the user's stored
     * hashes and, on a hit, consumes it: removes that one hash (preserving the
     * order of the rest), bumps the used-counter, and persists. Each recovery
     * code is therefore single-use.
     *
     * @param row       the user's 2FA row (mutated and saved on a match)
     * @param submitted the raw recovery code as typed (normalised before comparison)
     * @return true if a matching code was found and consumed; false otherwise
     */
    private boolean consumeRecoveryCode(UserTwoFactor row, String submitted) {
        String normalised = normaliseRecoveryCode(submitted);
        String[] hashes = row.getRecoveryCodesHashed();
        if (hashes == null) return false;
        for (int i = 0; i < hashes.length; i++) {
            if (passwordEncoder.matches(normalised, hashes[i])) {
                // Remove the consumed hash; preserves order for the rest.
                String[] remaining = new String[hashes.length - 1];
                System.arraycopy(hashes, 0, remaining, 0, i);
                System.arraycopy(hashes, i + 1, remaining, i, hashes.length - i - 1);
                row.setRecoveryCodesHashed(remaining);
                row.setRecoveryCodesUsed(row.getRecoveryCodesUsed() + 1);
                repository.save(row);
                log.info("2FA recovery code consumed for user={} (remaining={})",
                        row.getUserId(), remaining.length);
                return true;
            }
        }
        return false;
    }

    // ── Disable ────────────────────────────────────────────────────────────

    /**
     * Idempotent — disabling a not-enrolled user is a no-op (the UI may
     * fire this on logout-and-forget flows).
     *
     * <p>Deletes the row entirely (secret + recovery codes), so re-enabling
     * later goes through {@link #beginSetup} from scratch with a fresh secret.
     *
     * @param userId the user to un-enrol from 2FA
     */
    @Transactional
    public void disable(UUID userId) {
        repository.findById(userId).ifPresent(row -> {
            repository.delete(row);
            log.info("2FA disabled for user={}", userId);
        });
    }

    // ── Status ────────────────────────────────────────────────────────────

    /**
     * Reports the user's current 2FA state for display.
     *
     * @param userId the user to inspect
     * @return a {@link StatusView}; {@code enabled=false} with null timestamp and
     *         0 codes when the user is not enrolled
     */
    @Transactional(readOnly = true)
    public StatusView status(UUID userId) {
        return repository.findById(userId)
                .filter(UserTwoFactor::isEnabled)
                .map(row -> new StatusView(
                        true,
                        row.getEnabledAt(),
                        row.getRecoveryCodesHashed() == null ? 0 : row.getRecoveryCodesHashed().length))
                .orElseGet(() -> new StatusView(false, null, 0));
    }

    // ── TOTP core (RFC 6238) ──────────────────────────────────────────────

    /**
     * Visible-package-private for unit testing — verifies a TOTP code
     * against a given instant. Production callers use {@link #verify}
     * / {@link #enable} which pass {@code Instant.now()}.
     *
     * <p>Checks the current 30-second step and ±{@value #TOLERANCE_STEPS} steps
     * to tolerate clock drift / roll-over. The comparison is computed across the
     * whole window without short-circuiting to avoid leaking which step matched.
     * A non-6-digit input or an undecodable secret returns false rather than
     * throwing.
     *
     * @param secret the base32-encoded shared secret
     * @param code   the candidate 6-digit code
     * @param now    the instant to evaluate the code at
     * @return true if {@code code} matches the expected TOTP within tolerance
     */
    boolean verifyTotp(String secret, String code, Instant now) {
        if (code == null || !code.matches("\\d{6}")) return false;
        byte[] key;
        try {
            key = base32Decode(secret);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        int submitted = Integer.parseInt(code);
        long currentStep = now.getEpochSecond() / TIME_STEP_SECONDS;
        // Constant-time across the tolerance window — don't short-circuit
        // on first match (timing side-channel of which step matched is not
        // sensitive but cheap to avoid).
        boolean matched = false;
        for (int delta = -TOLERANCE_STEPS; delta <= TOLERANCE_STEPS; delta++) {
            int expected = generateCodeForStep(key, currentStep + delta);
            // Bitwise OR keeps the loop branch-free w.r.t. the match result.
            matched |= (expected == submitted);
        }
        return matched;
    }

    /**
     * Pure function exposed for tests — generate the TOTP code for a given step.
     *
     * <p>Implements RFC 4226 HMAC-SHA1 + dynamic truncation: HMAC the 8-byte
     * big-endian step counter with {@code key}, take the offset from the low
     * nibble of the last byte, read a 31-bit integer from there, and reduce mod
     * {@value #CODE_MODULO} to a 6-digit code.
     *
     * @param key  the raw (decoded) HMAC key bytes
     * @param step the time-step counter (epoch seconds / {@value #TIME_STEP_SECONDS})
     * @return the 6-digit TOTP value for that step
     * @throws IllegalStateException if HMAC-SHA1 is unavailable in the JVM (effectively unreachable)
     */
    static int generateCodeForStep(byte[] key, long step) {
        byte[] data = ByteBuffer.allocate(Long.BYTES).putLong(step).array();
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            byte[] hmac = mac.doFinal(data);
            // RFC 4226 §5.4 dynamic truncation — last nibble is the offset.
            int offset = hmac[hmac.length - 1] & 0x0F;
            int binary = ((hmac[offset]     & 0x7F) << 24)
                       | ((hmac[offset + 1] & 0xFF) << 16)
                       | ((hmac[offset + 2] & 0xFF) << 8)
                       |  (hmac[offset + 3] & 0xFF);
            return binary % CODE_MODULO;
        } catch (Exception ex) {
            // HMAC-SHA1 is in every JCE provider; this branch is unreachable
            // outside of a deliberately broken JVM. Re-wrap so callers don't
            // see a checked exception.
            throw new IllegalStateException("HMAC-SHA1 unavailable", ex);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Generates a new {@value #SECRET_BYTE_LENGTH}-byte ({@code 160}-bit)
     * cryptographically random secret and returns it base32-encoded for the
     * authenticator app.
     *
     * @return the base32-encoded TOTP secret
     */
    private String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTE_LENGTH];
        random.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /**
     * Generates {@value #RECOVERY_CODE_COUNT} random recovery codes, each
     * {@value #RECOVERY_CODE_CHARS} base32 characters formatted as
     * {@code XXXX-XXXX} for readability. These are the plaintext codes shown to
     * the user once; only their bcrypt hashes are persisted.
     *
     * @return the list of display-formatted recovery codes
     */
    private List<String> generateRecoveryCodes() {
        List<String> codes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            StringBuilder sb = new StringBuilder(RECOVERY_CODE_CHARS + 1);
            for (int c = 0; c < RECOVERY_CODE_CHARS; c++) {
                if (c == RECOVERY_CODE_CHARS / 2) sb.append('-');
                sb.append(BASE32_ALPHABET.charAt(random.nextInt(BASE32_ALPHABET.length())));
            }
            codes.add(sb.toString());
        }
        return codes;
    }

    /**
     * Strip whitespace + dashes, uppercase. Symmetric between hash + verify.
     *
     * <p>Applied identically when hashing a code for storage and when checking
     * a submitted one, so a code pasted with or without its {@code -} separator
     * still matches.
     *
     * @param raw the recovery code as entered (may be null)
     * @return the canonical form (empty string for null input)
     */
    private String normaliseRecoveryCode(String raw) {
        if (raw == null) return "";
        return raw.trim().toUpperCase().replace("-", "").replaceAll("\\s+", "");
    }

    /**
     * Build the {@code otpauth://} URI scanned by authenticator apps.
     * Issuer doubled (in path label + query param) per the de-facto
     * convention — both Google and Microsoft authenticators read either,
     * but some prefer the path form and some the query form.
     *
     * @param accountLabel the per-user label (typically email) shown in the app
     * @param secret       the base32 secret to embed
     * @return an {@code otpauth://totp/...} URI ready to render as a QR code
     */
    static String buildOtpauthUri(String accountLabel, String secret) {
        String issuer = urlEncode("Sber DLMM");
        String label = urlEncode("Sber DLMM:" + accountLabel);
        return "otpauth://totp/" + label
                + "?secret=" + secret
                + "&issuer=" + issuer
                + "&algorithm=SHA1"
                + "&digits=" + CODE_DIGITS
                + "&period=" + TIME_STEP_SECONDS;
    }

    /**
     * UTF-8 URL-encodes a component for safe inclusion in the otpauth URI.
     *
     * @param s the raw string to encode
     * @return the percent-encoded string
     */
    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ── Base32 (RFC 4648, no padding) ─────────────────────────────────────
    // Inline implementation — Apache Commons Codec would also work, but
    // adding a transitive dep for ~30 lines is overkill.

    /**
     * Encodes bytes to an unpadded RFC 4648 base32 string using the
     * authenticator-standard {@value #BASE32_ALPHABET} alphabet. Package-private
     * and {@code static} for direct unit testing.
     *
     * @param data the bytes to encode (empty array yields an empty string)
     * @return the base32-encoded representation
     */
    static String base32Encode(byte[] data) {
        if (data.length == 0) return "";
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = data[0] & 0xFF;
        int next = 1;
        int bitsLeft = 8;
        while (bitsLeft > 0 || next < data.length) {
            if (bitsLeft < 5) {
                if (next < data.length) {
                    buffer <<= 8;
                    buffer |= (data[next++] & 0xFF);
                    bitsLeft += 8;
                } else {
                    int pad = 5 - bitsLeft;
                    buffer <<= pad;
                    bitsLeft += pad;
                }
            }
            int index = 0x1F & (buffer >> (bitsLeft - 5));
            bitsLeft -= 5;
            sb.append(BASE32_ALPHABET.charAt(index));
        }
        return sb.toString();
    }

    /**
     * Decodes an RFC 4648 base32 string (case-insensitive, whitespace
     * tolerated) back to bytes. Package-private and {@code static} for direct
     * unit testing; {@link #verifyTotp} relies on the
     * {@link IllegalArgumentException} to reject a corrupt secret as a failed
     * verification rather than a 500.
     *
     * @param s the base32 text to decode
     * @return the decoded bytes (empty array for empty/whitespace input)
     * @throws IllegalArgumentException if {@code s} is null or contains a non-base32 character
     */
    static byte[] base32Decode(String s) {
        if (s == null) throw new IllegalArgumentException("null base32 input");
        String cleaned = s.trim().toUpperCase().replaceAll("\\s+", "");
        if (cleaned.isEmpty()) return new byte[0];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            int value = BASE32_ALPHABET.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Invalid base32 char: " + c);
            }
            buffer <<= 5;
            buffer |= value & 0x1F;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }
}
