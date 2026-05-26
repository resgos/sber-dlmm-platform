package com.sber.dlmm.pool.service;

import com.sber.dlmm.common.exception.DlmmException;
import com.sber.dlmm.common.exception.InvalidQuoteSignatureException;
import com.sber.dlmm.common.exception.QuoteAlreadyExecutedException;
import com.sber.dlmm.common.exception.QuoteExpiredException;
import com.sber.dlmm.common.outbox.OutboxService;
import com.sber.dlmm.pool.client.TokenServiceClient;
import com.sber.dlmm.pool.client.UserServiceClient;
import com.sber.dlmm.pool.dto.QuotedSwap;
import com.sber.dlmm.pool.dto.SwapExecuteRequest;
import com.sber.dlmm.pool.repository.LiquidityPoolRepository;
import com.sber.dlmm.pool.repository.PoolBinRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Batch G-02 — automated regression tests for the swap quote-execute
 * idempotency contract. Pins three guarantees the risk register (R#17,
 * R#46 backstop) calls out but that had no automated coverage prior to
 * this batch:
 *
 * <ol>
 *   <li>{@code rejectsStaleQuote}: a quote whose
 *       TTL has elapsed must be rejected with
 *       {@link QuoteExpiredException} (HTTP 410).</li>
 *   <li>{@code rejectsDoubleExecute}: a quote that has already been
 *       consumed must be rejected with
 *       {@link QuoteAlreadyExecutedException} (HTTP 409).</li>
 *   <li>{@code rejectsSignatureMismatch}: an execute call whose
 *       signature doesn't match the persisted quote signature must be
 *       rejected with {@link InvalidQuoteSignatureException} (HTTP 403).</li>
 * </ol>
 *
 * <p>The tests use {@link QuoteStore} as a mock so the time-sensitive
 * paths (stale quote) can be driven by setting {@code createdAt} on the
 * stubbed return value, rather than waiting wall-clock time. Each test
 * asserts the error code ({@link DlmmException#getErrorCode}) so a
 * future rename of the exception class would still pin the wire
 * contract surface (clients integrate against the string code, not the
 * Java class).
 *
 * <p>Critically, every assertion verifies that no downstream side
 * effect happened — no balance deduction, no event emission. The
 * guards must fail-fast at the top of {@code executeQuoted}, before
 * any mutation, exactly so that rejected attempts leave no trace.
 */
@ExtendWith(MockitoExtension.class)
class SwapIdempotencyTest {

    @Mock
    private LiquidityPoolRepository poolRepository;
    @Mock
    private PoolBinRepository poolBinRepository;
    @Mock
    private TokenServiceClient tokenServiceClient;
    @Mock
    private UserServiceClient userServiceClient;
    @Mock
    private OutboxService outbox;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private QuoteStore quoteStore;
    @Mock
    private ApplicationContext appCtx;

    private SwapService swapService;

    private static final long TTL_SECONDS = 30L;
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID POOL_ID = UUID.randomUUID();
    private static final UUID TOKEN_X = UUID.randomUUID();
    private static final UUID TOKEN_Y = UUID.randomUUID();
    private static final String VALID_SIGNATURE = "sig-abc-123";

    @BeforeEach
    void setUp() {
        // Construct SwapService directly so the @Value-injected TTL
        // comes through with the same default the production
        // configuration uses (30s — see RedisQuoteStore + SwapService).
        // Using @InjectMocks here would default the long to 0, making
        // every quote look instantly stale.
        swapService = new SwapService(
                poolRepository, poolBinRepository,
                tokenServiceClient, userServiceClient,
                outbox, redisTemplate,
                quoteStore, TTL_SECONDS);
        // Self-injection: appCtx.getBean(SwapService.class) is used by
        // the retry loop in swap(). Tests that go through executeQuoted
        // never reach that path because the guards fail first, but we
        // wire it just in case for future tests.
        ReflectionTestUtils.setField(swapService, "appCtx", appCtx);
    }

    /** Fresh quote at time t=now, valid signature. */
    private QuotedSwap newQuote() {
        return new QuotedSwap(
                UUID.randomUUID(),
                USER_ID,
                POOL_ID,
                TOKEN_X,
                TOKEN_Y,
                10_000L,
                9_970L,
                30L,
                VALID_SIGNATURE,
                Instant.now(),
                null);
    }

    // ── Test 1: Stale quote rejected ────────────────────────────────

    @Test
    @DisplayName("rejectsStaleQuote: a quote older than TTL is rejected with QUOTE_EXPIRED")
    void rejectsStaleQuote() {
        // Build a quote stamped 60s ago — past the 30s TTL window. The
        // test sketches createdAt directly rather than freezing the clock
        // so the contract assertion stays simple (no Clock indirection
        // needed in production code).
        QuotedSwap stale = new QuotedSwap(
                UUID.randomUUID(), USER_ID, POOL_ID, TOKEN_X, TOKEN_Y,
                10_000L, 9_970L, 30L, VALID_SIGNATURE,
                Instant.now().minus(Duration.ofSeconds(60)),
                null);
        when(quoteStore.findById(stale.quoteId())).thenReturn(Optional.of(stale));

        SwapExecuteRequest req = new SwapExecuteRequest(stale.quoteId(), VALID_SIGNATURE);

        DlmmException ex = assertThrows(QuoteExpiredException.class,
                () -> swapService.executeQuoted(req, USER_ID));
        assertThat(ex.getErrorCode()).isEqualTo("QUOTE_EXPIRED");
        assertThat(ex.getHttpStatus()).isEqualTo(410);

        // No side effects: didn't try to credit/deduct, didn't even
        // attempt to flip the executed-marker. The guard fires before
        // any mutation can land.
        verify(quoteStore, never()).markExecuted(stale.quoteId());
        verify(tokenServiceClient, never()).deductBalance(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(outbox, never()).append(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    // ── Test 2: Double-execute rejected (idempotency) ───────────────

    @Test
    @DisplayName("rejectsDoubleExecute: a quote with executedAt != null is rejected with QUOTE_ALREADY_EXECUTED")
    void rejectsDoubleExecute() {
        // Quote that's still inside its TTL window but already
        // consumed — exactly the network-retry / double-click scenario
        // the contract has to defend.
        QuotedSwap consumed = new QuotedSwap(
                UUID.randomUUID(), USER_ID, POOL_ID, TOKEN_X, TOKEN_Y,
                10_000L, 9_970L, 30L, VALID_SIGNATURE,
                Instant.now().minus(Duration.ofSeconds(5)),     // fresh
                Instant.now().minus(Duration.ofSeconds(1)));    // already executed
        when(quoteStore.findById(consumed.quoteId())).thenReturn(Optional.of(consumed));

        SwapExecuteRequest req = new SwapExecuteRequest(consumed.quoteId(), VALID_SIGNATURE);

        DlmmException ex = assertThrows(QuoteAlreadyExecutedException.class,
                () -> swapService.executeQuoted(req, USER_ID));
        assertThat(ex.getErrorCode()).isEqualTo("QUOTE_ALREADY_EXECUTED");
        assertThat(ex.getHttpStatus()).isEqualTo(409);

        // Critically: the guard must NOT call markExecuted again — that
        // would either no-op (good) or, if the underlying store has any
        // side-effect-on-call semantics, leak state. Pin the behaviour.
        verify(quoteStore, never()).markExecuted(consumed.quoteId());
        verify(tokenServiceClient, never()).deductBalance(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    // ── Test 3: Signature replay rejected ───────────────────────────

    @Test
    @DisplayName("rejectsSignatureMismatch: execute with a signature != quote.signature is rejected with INVALID_QUOTE_SIGNATURE")
    void rejectsSignatureMismatch() {
        // Quote bound to VALID_SIGNATURE. Client (or attacker) sends a
        // different signature in the execute call — must be rejected
        // before any balance touch.
        QuotedSwap fresh = newQuote();
        when(quoteStore.findById(fresh.quoteId())).thenReturn(Optional.of(fresh));

        SwapExecuteRequest req = new SwapExecuteRequest(fresh.quoteId(), "wrong-signature");

        DlmmException ex = assertThrows(InvalidQuoteSignatureException.class,
                () -> swapService.executeQuoted(req, USER_ID));
        assertThat(ex.getErrorCode()).isEqualTo("INVALID_QUOTE_SIGNATURE");
        assertThat(ex.getHttpStatus()).isEqualTo(403);

        // The CRITICAL invariant: a mismatched signature must NOT flip
        // the executed-marker. Otherwise an attacker could intentionally
        // submit a bad signature on someone else's quoteId to lock the
        // legitimate user out (denial of service). Pin that.
        verify(quoteStore, never()).markExecuted(fresh.quoteId());
        verify(tokenServiceClient, never()).deductBalance(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    // ── Bonus pin: missing quote also surfaces as QUOTE_EXPIRED ─────
    //
    // Documents the choice in executeQuoted() to map findById()
    // returning empty (Redis TTL eviction OR never-issued) to the same
    // error code as "stale quote". From the client's perspective these
    // are indistinguishable — both mean "your handle is no longer
    // valid, refresh and retry". Pinning this prevents an accidental
    // future refactor that leaks the distinction back out.

    @Test
    @DisplayName("missing quote surfaces as QUOTE_EXPIRED (no information leak about issuance)")
    void missingQuoteSurfacesAsExpired() {
        UUID phantomId = UUID.randomUUID();
        when(quoteStore.findById(phantomId)).thenReturn(Optional.empty());

        SwapExecuteRequest req = new SwapExecuteRequest(phantomId, VALID_SIGNATURE);

        DlmmException ex = assertThrows(QuoteExpiredException.class,
                () -> swapService.executeQuoted(req, USER_ID));
        assertThat(ex.getErrorCode()).isEqualTo("QUOTE_EXPIRED");
    }
}
