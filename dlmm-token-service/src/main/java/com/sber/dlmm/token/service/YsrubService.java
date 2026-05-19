package com.sber.dlmm.token.service;

import com.sber.dlmm.common.exception.InsufficientBalanceException;
import com.sber.dlmm.common.exception.TokenNotFoundException;
import com.sber.dlmm.common.outbox.OutboxService;
import com.sber.dlmm.token.entity.Token;
import com.sber.dlmm.token.entity.UserBalance;
import com.sber.dlmm.token.entity.YsrubReserveMovement;
import com.sber.dlmm.token.event.YsrubBurnedEvent;
import com.sber.dlmm.token.event.YsrubMintedEvent;
import com.sber.dlmm.token.repository.TokenRepository;
import com.sber.dlmm.token.repository.UserBalanceRepository;
import com.sber.dlmm.token.repository.YsrubReserveMovementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Sprint 9 #7.1 — Tokenized money market (YSRUB).
 *
 * <p>Two operations:
 * <ul>
 *   <li>{@link #mint(UUID, long, String)} — deposit SRUB → credit YSRUB
 *       at the current ratio (1:1 today; yield-adjusted Sprint 10+).
 *       SRUB is moved from the user's balance into the reserve account.</li>
 *   <li>{@link #burn(UUID, long, String)} — burn YSRUB → return SRUB
 *       at the current ratio. SRUB moves from reserve back to user.</li>
 * </ul>
 *
 * <p><b>Reserve account model</b>: the SRUB locked when YSRUB is minted
 * sits in {@link #reserveAccountId} — a fixed system UUID. Sprint 10's
 * #7.6 daily reserve-health attestation queries this account's balance
 * vs total YSRUB outstanding to publish the 1:1 backing proof.
 *
 * <p><b>Idempotency</b>: every operation requires an {@code idempotencyKey}.
 * Duplicate keys are detected via DB UNIQUE constraint on
 * {@code ysrub_reserve_movements.idempotency_key} and short-circuit to
 * the existing movement (no double-mint).
 *
 * <p><b>What's not here (Sprint 10+)</b>:
 * <ul>
 *   <li>KYC pre-check — relies on gateway-layer X-Kyc-Status header
 *       (current platform pattern). Service-layer enforcement
 *       (call user-service like pool-engine does) is the proper next step.</li>
 *   <li>Per-user limits, AML thresholds — Sprint 10 anti-abuse pass.</li>
 *   <li>Yield-adjusted ratio — Sprint 10 once {@code YieldDistributionScheduler}
 *       starts accruing yield into the unit value.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class YsrubService {

    private static final String TOPIC = "token-events";
    private static final String SRUB_SYMBOL = "SRUB";
    private static final String YSRUB_SYMBOL = "YSRUB";
    /** Sprint 9 MVP — 1 SRUB → 1 YSRUB. Sprint 10 will replace with
        the yield-adjusted ratio queried from YieldRatioService. */
    private static final long INITIAL_RATIO_MICRO = 1_000_000L;
    /** Fixed system user UUID that holds the SRUB reserve. Pre-seeded via
        init-db.sql ('a0000000-0000-0000-0000-000000000099' reserved for this
        purpose; falls back to deterministic UUID if not present). */
    private static final UUID RESERVE_ACCOUNT_ID =
            UUID.fromString("a0000000-0000-0000-0000-000000000099");

    private final TokenRepository tokenRepository;
    private final UserBalanceRepository userBalanceRepository;
    private final YsrubReserveMovementRepository reserveRepository;
    private final OutboxService outbox;

    /**
     * Mint YSRUB by depositing SRUB.
     *
     * @param userId         caller (verified via gateway JWT)
     * @param srubAmount     amount of SRUB to deposit (smallest unit, must be > 0)
     * @param idempotencyKey unique per request; replay-safe
     * @return the resulting movement record
     * @throws InsufficientBalanceException if user's SRUB available &lt; srubAmount
     */
    @Transactional
    public YsrubReserveMovement mint(UUID userId, long srubAmount, String idempotencyKey) {
        validateAmount(srubAmount, "srubAmount");
        Optional<YsrubReserveMovement> existing = reserveRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.info("Mint idempotency hit: jti={} → returning existing movement {}",
                    idempotencyKey, existing.get().getId());
            return existing.get();
        }

        UUID srubId = resolveTokenId(SRUB_SYMBOL);
        UUID ysrubId = resolveTokenId(YSRUB_SYMBOL);

        // 1) Move SRUB from user to reserve (atomic deduct + credit).
        int deducted = userBalanceRepository.deductAvailable(userId, srubId, srubAmount);
        if (deducted == 0) {
            throw new InsufficientBalanceException(
                    "Insufficient SRUB available for user " + userId + " (requested " + srubAmount + ")");
        }
        creditOrCreate(RESERVE_ACCOUNT_ID, srubId, srubAmount);

        // 2) Credit YSRUB to user at current ratio.
        long ysrubAmount = applyRatio(srubAmount, INITIAL_RATIO_MICRO);
        creditOrCreate(userId, ysrubId, ysrubAmount);

        // 3) Bump token totalSupply.
        Token ysrubToken = tokenRepository.findById(ysrubId)
                .orElseThrow(() -> new TokenNotFoundException("YSRUB token row missing"));
        ysrubToken.setTotalSupply(ysrubToken.getTotalSupply() + ysrubAmount);
        tokenRepository.save(ysrubToken);

        // 4) Persist movement for audit + idempotency.
        YsrubReserveMovement movement = reserveRepository.save(YsrubReserveMovement.builder()
                .userId(userId)
                .direction(YsrubReserveMovement.Direction.DEPOSIT)
                .srubAmount(srubAmount)
                .ysrubAmount(ysrubAmount)
                .ratioMicro(INITIAL_RATIO_MICRO)
                .idempotencyKey(idempotencyKey)
                .build());

        // 5) Outbox event (drained to Kafka by OutboxDispatcher).
        outbox.append("ysrub", movement.getId().toString(), "YsrubMinted", TOPIC,
                new YsrubMintedEvent(movement.getId(), userId, srubAmount, ysrubAmount,
                        INITIAL_RATIO_MICRO, reserveRepository.totalReserveSrub(),
                        LocalDateTime.now()));

        log.info("YSRUB minted: user={} srub={} ysrub={} (1:1 ratio) movement={}",
                userId, srubAmount, ysrubAmount, movement.getId());
        return movement;
    }

    /**
     * Burn YSRUB and withdraw SRUB.
     *
     * @param userId         caller (verified via gateway JWT)
     * @param ysrubAmount    amount of YSRUB to burn (smallest unit, must be > 0)
     * @param idempotencyKey unique per request
     * @return movement record
     * @throws InsufficientBalanceException if user's YSRUB available &lt; ysrubAmount
     * @throws IllegalStateException if reserve doesn't have enough SRUB (should
     *         never happen with 1:1 backing — fail-loud signal of accounting drift)
     */
    @Transactional
    public YsrubReserveMovement burn(UUID userId, long ysrubAmount, String idempotencyKey) {
        validateAmount(ysrubAmount, "ysrubAmount");
        Optional<YsrubReserveMovement> existing = reserveRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.info("Burn idempotency hit: jti={} → returning existing movement {}",
                    idempotencyKey, existing.get().getId());
            return existing.get();
        }

        UUID srubId = resolveTokenId(SRUB_SYMBOL);
        UUID ysrubId = resolveTokenId(YSRUB_SYMBOL);
        long srubAmount = applyRatio(ysrubAmount, INITIAL_RATIO_MICRO);

        // 1) Deduct YSRUB from user.
        int ysrubDeducted = userBalanceRepository.deductAvailable(userId, ysrubId, ysrubAmount);
        if (ysrubDeducted == 0) {
            throw new InsufficientBalanceException(
                    "Insufficient YSRUB available for user " + userId + " (requested " + ysrubAmount + ")");
        }

        // 2) Deduct SRUB from reserve, credit user. If reserve is short,
        //    fail loud — this signals a reserve accounting drift that
        //    needs operator attention immediately (1:1 invariant broken).
        int reserveDeducted = userBalanceRepository.deductAvailable(RESERVE_ACCOUNT_ID, srubId, srubAmount);
        if (reserveDeducted == 0) {
            throw new IllegalStateException("YSRUB RESERVE SHORTFALL — reserve account "
                    + RESERVE_ACCOUNT_ID + " has insufficient SRUB to redeem " + srubAmount
                    + " for user " + userId + ". Manual reconciliation required.");
        }
        creditOrCreate(userId, srubId, srubAmount);

        // 3) Decrement YSRUB totalSupply.
        Token ysrubToken = tokenRepository.findById(ysrubId)
                .orElseThrow(() -> new TokenNotFoundException("YSRUB token row missing"));
        ysrubToken.setTotalSupply(ysrubToken.getTotalSupply() - ysrubAmount);
        tokenRepository.save(ysrubToken);

        // 4) Persist movement.
        YsrubReserveMovement movement = reserveRepository.save(YsrubReserveMovement.builder()
                .userId(userId)
                .direction(YsrubReserveMovement.Direction.WITHDRAWAL)
                .srubAmount(srubAmount)
                .ysrubAmount(ysrubAmount)
                .ratioMicro(INITIAL_RATIO_MICRO)
                .idempotencyKey(idempotencyKey)
                .build());

        // 5) Outbox event.
        outbox.append("ysrub", movement.getId().toString(), "YsrubBurned", TOPIC,
                new YsrubBurnedEvent(movement.getId(), userId, ysrubAmount, srubAmount,
                        INITIAL_RATIO_MICRO, reserveRepository.totalReserveSrub(),
                        LocalDateTime.now()));

        log.info("YSRUB burned: user={} ysrub={} srub={} (1:1 ratio) movement={}",
                userId, ysrubAmount, srubAmount, movement.getId());
        return movement;
    }

    // ── helpers ──

    private void validateAmount(long amount, String field) {
        if (amount <= 0) {
            throw new IllegalArgumentException(field + " must be positive (was " + amount + ")");
        }
    }

    private UUID resolveTokenId(String symbol) {
        return tokenRepository.findBySymbol(symbol)
                .orElseThrow(() -> new TokenNotFoundException(
                        "Token " + symbol + " not found — check init-db.sql + Liquibase changeset 008c"))
                .getId();
    }

    /** Multiply amount by ratio (in micros). 1 SRUB × 1_000_000 micro / 1_000_000 = 1 YSRUB. */
    private static long applyRatio(long amount, long ratioMicro) {
        return Math.multiplyExact(amount, ratioMicro) / 1_000_000L;
    }

    /**
     * Atomic credit + create-if-missing. The deduct + credit pair must
     * be inside a single Spring transaction; @Transactional on mint/burn
     * provides that.
     */
    private void creditOrCreate(UUID userId, UUID tokenId, long amount) {
        int updated = userBalanceRepository.creditAvailable(userId, tokenId, amount);
        if (updated == 0) {
            // No existing row — insert and retry.
            UserBalance fresh = new UserBalance();
            fresh.setUserId(userId);
            fresh.setTokenId(tokenId);
            fresh.setAvailable(0);
            fresh.setLocked(0);
            userBalanceRepository.save(fresh);
            int retried = userBalanceRepository.creditAvailable(userId, tokenId, amount);
            if (retried == 0) {
                throw new IllegalStateException("Failed to credit balance after row create for user "
                        + userId + " token " + tokenId);
            }
        }
    }
}
