package com.sber.dlmm.token.service;

import com.sber.dlmm.token.entity.SpasiboOperation;
import com.sber.dlmm.token.repository.SpasiboOperationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Sprint 5 #5.3 + #5.4 — SberSpasibo loyalty operations.
 *
 * <p>Two flows:
 * <ol>
 *   <li><b>Webhook MINT</b> (#5.3): SberSpasibo BU pushes "user earned N points"
 *       events; we credit SSPAS to the user's balance idempotently by reference.</li>
 *   <li><b>User CONVERT</b> (#5.4): user-initiated conversion of SSPAS → SRUB at
 *       a fixed rate (default 1 SSPAS = 1 SRUB unit). Zero fee for retail.</li>
 * </ol>
 *
 * <p>Idempotency: {@link SpasiboOperation#getReference()} is the unique anchor.
 * Re-running the same reference returns the existing row, never re-applying
 * balance changes. The mint webhook from Spasibo BU is at-least-once delivery
 * — without this dedup, double-credit on retries is a real risk.
 *
 * <p>Conversion is a direct burn-mint pair (NOT a DLMM swap) so it doesn't
 * depend on SSPAS/SRUB pool liquidity and always succeeds at the configured
 * rate. The pool seeded by 04-spasibo-seed.sql is for users who prefer the
 * regular swap UX (and accept price impact).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpasiboService {

    private final SpasiboOperationRepository operationRepository;
    private final TokenService tokenService;

    /** Hex UUID of the SSPAS token from 04-spasibo-seed.sql. Overridable via config. */
    @Value("${dlmm.spasibo.token-id:b0000000-0000-0000-0000-000000000201}")
    private UUID spasiboTokenId;

    /** Hex UUID of the SRUB token from init-db.sql seed. */
    @Value("${dlmm.spasibo.rub-token-id:b0000000-0000-0000-0000-000000000001}")
    private UUID rubTokenId;

    /**
     * Points-to-SRUB-unit conversion rate. Default 1 means 1 SSPAS unit = 1
     * SRUB unit (both stored in raw token units; the *display* conversion of
     * "100 баллов = 1 рубль" is a UI concern via token.decimals).
     */
    @Value("${dlmm.spasibo.conversion-rate:1}")
    private long conversionRate;

    /**
     * Idempotent webhook handler — mints SSPAS to the given user. Re-invocation
     * with the same reference returns the existing operation. Race: two
     * concurrent calls with the same reference may both pass the dedup check
     * but only one will succeed the unique-constraint insert; the loser is
     * retried and finds the winner's row on the second pass.
     *
     * @param userId    user to credit SSPAS to
     * @param points    points earned; credited 1:1 as raw SSPAS units (must be &gt; 0)
     * @param reference Spasibo BU event reference; the idempotency anchor (required)
     * @return the existing operation on replay, otherwise the newly recorded MINT op
     * @throws IllegalArgumentException if {@code points <= 0} or {@code reference} is blank
     */
    @Transactional
    public SpasiboOperation handleMintWebhook(UUID userId, long points, String reference) {
        if (points <= 0) {
            throw new IllegalArgumentException("Points must be positive, got " + points);
        }
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Reference required for webhook idempotency");
        }

        Optional<SpasiboOperation> existing = operationRepository.findByReference(reference);
        if (existing.isPresent()) {
            log.info("Spasibo MINT webhook replayed reference={} userId={} — returning existing op id={}",
                    reference, userId, existing.get().getId());
            return existing.get();
        }

        // Credit SSPAS to user via the existing internal-balance path —
        // ensures consistent event emission + audit through tokenService.
        tokenService.creditInternal(userId, spasiboTokenId, points);

        SpasiboOperation op = SpasiboOperation.builder()
                .opType(SpasiboOperation.OpType.MINT)
                .userId(userId)
                .points(points)
                .reference(reference)
                .status(SpasiboOperation.Status.COMPLETED)
                .build();
        SpasiboOperation saved = operationRepository.save(op);
        log.info("Spasibo MINT id={} userId={} +{} SSPAS reference={}",
                saved.getId(), userId, points, reference);
        return saved;
    }

    /**
     * Idempotent SSPAS → SRUB conversion at fixed rate. Burns SSPAS from
     * caller, credits SRUB equivalent. Both happen in this transaction —
     * either both succeed or both roll back (no half-credited state).
     *
     * <p>Rate currently linear: rubAmount = points × conversionRate. For
     * tiered rates ("first 1000 points at 1.5×, then 1×") swap the rate
     * field for a rate-table lookup in Sprint 6+.
     *
     * @param userId    user whose SSPAS is burned and SRUB credited
     * @param points    SSPAS points to convert (must be &gt; 0)
     * @param reference client-supplied conversion reference; the idempotency anchor (required)
     * @return the existing operation on replay, otherwise the newly recorded CONVERT op
     * @throws IllegalArgumentException if {@code points <= 0} or {@code reference} is blank
     * @throws com.sber.dlmm.common.exception.InsufficientBalanceException if the user lacks the SSPAS to burn
     */
    @Transactional
    public SpasiboOperation convertToRub(UUID userId, long points, String reference) {
        if (points <= 0) {
            throw new IllegalArgumentException("Points must be positive, got " + points);
        }
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Reference required for conversion idempotency");
        }

        Optional<SpasiboOperation> existing = operationRepository.findByReference(reference);
        if (existing.isPresent()) {
            log.info("Spasibo CONVERT replayed reference={} userId={} — returning existing op id={}",
                    reference, userId, existing.get().getId());
            return existing.get();
        }

        long rubAmount = points * conversionRate;

        // Burn SSPAS first — fail-fast on insufficient balance, no SRUB is created
        // for free. Single @Transactional wraps both so a credit-side failure
        // also rolls back the deduct (no orphaned SSPAS-loss).
        tokenService.deductInternal(userId, spasiboTokenId, points);
        tokenService.creditInternal(userId, rubTokenId, rubAmount);

        SpasiboOperation op = SpasiboOperation.builder()
                .opType(SpasiboOperation.OpType.CONVERT)
                .userId(userId)
                .points(points)
                .rubAmount(rubAmount)
                .reference(reference)
                .status(SpasiboOperation.Status.COMPLETED)
                .build();
        SpasiboOperation saved = operationRepository.save(op);
        log.info("Spasibo CONVERT id={} userId={} -{} SSPAS +{} SRUB reference={}",
                saved.getId(), userId, points, rubAmount, reference);
        return saved;
    }
}
