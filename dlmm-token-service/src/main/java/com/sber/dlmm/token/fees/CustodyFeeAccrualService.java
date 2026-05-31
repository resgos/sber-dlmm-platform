package com.sber.dlmm.token.fees;

import com.sber.dlmm.token.entity.UserBalance;
import com.sber.dlmm.common.outbox.OutboxService;
import com.sber.dlmm.token.repository.TokenRepository;
import com.sber.dlmm.token.repository.UserBalanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Per-row custody-fee accrual. Lives in its own bean so the
 * {@link CustodyFeeJob} can invoke {@code accrueOne(...)} through the
 * Spring proxy — self-invocation from inside the scheduler bean would
 * bypass {@code @Transactional} and the JPA Modifying queries would
 * fail with TransactionRequiredException at the first tick.
 *
 * REQUIRES_NEW propagation: one bad row should not roll back the whole
 * batch. The scheduler iterates and calls this method per row, each in
 * its own transaction.
 */
@Service
public class CustodyFeeAccrualService {

    private static final Logger log = LoggerFactory.getLogger(CustodyFeeAccrualService.class);
    private static final long BPS_DIVISOR = 10_000L;
    private static final long DAYS_IN_YEAR = 365L;
    private static final long MAX_ACCRUAL_DAYS = 90L;

    private final UserBalanceRepository userBalanceRepository;
    private final TokenRepository tokenRepository;
    private final OutboxService outbox;

    public CustodyFeeAccrualService(UserBalanceRepository userBalanceRepository,
                                    TokenRepository tokenRepository,
                                    OutboxService outbox) {
        this.userBalanceRepository = userBalanceRepository;
        this.tokenRepository = tokenRepository;
        this.outbox = outbox;
    }

    /**
     * Accrue custody fee for one balance row. Returns the fee taken
     * (0 if skipped). Each call is its own DB transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long accrueOne(UserBalance b, LocalDateTime now, int custodyBpsPerAnnum,
                          UUID treasuryUserId) {
        LocalDateTime since = b.getLastCustodyFeeAt() != null
                ? b.getLastCustodyFeeAt()
                : b.getUpdatedAt();
        long days = Math.min(MAX_ACCRUAL_DAYS, Duration.between(since, now).toDays());
        if (days <= 0) return 0;

        // Advance the watermark by the whole days we actually charge — NOT to
        // `now`. Duration.toDays() floors and `days` is capped at
        // MAX_ACCRUAL_DAYS, so marking `now` silently drops the sub-day
        // remainder (and any time beyond the 90-day cap) every tick, which
        // systematically under-charges custody over time. Carrying the
        // remainder forward makes accrual lossless (#27).
        LocalDateTime accruedThrough = since.plusDays(days);

        // Overflow-safe: after the 1e-4 platform amount scale, `available` is a
        // large raw integer (≈1e15+ for a treasury/whale row), so
        // `available * custodyBpsPerAnnum * days` overflows long and would wrap
        // NEGATIVE → the fee<=0 branch → a silent zero-charge on exactly the
        // biggest balances. Same hazard the swap-fee path was hardened for.
        long fee = java.math.BigInteger.valueOf(b.getAvailable())
                .multiply(java.math.BigInteger.valueOf(custodyBpsPerAnnum))
                .multiply(java.math.BigInteger.valueOf(days))
                .divide(java.math.BigInteger.valueOf(BPS_DIVISOR * DAYS_IN_YEAR))
                .longValue();

        if (fee <= 0) {
            // Sub-unit accrual → still mark accrued so the cron doesn't
            // re-evaluate this row every tick forever. Uses a dedicated
            // UPDATE rather than entity.save() to avoid round-tripping
            // stale `available` (see markCustodyAccrued javadoc).
            userBalanceRepository.markCustodyAccrued(b.getUserId(), b.getTokenId(), accruedThrough);
            return 0;
        }
        if (fee >= b.getAvailable()) {
            log.warn("Custody fee {} >= available {} on userId={} tokenId={}, skipping",
                    fee, b.getAvailable(), b.getUserId(), b.getTokenId());
            return 0;
        }

        int updated = userBalanceRepository.deductAvailable(
                b.getUserId(), b.getTokenId(), fee);
        if (updated == 0) {
            // Concurrent write raced us. Try again next tick.
            return 0;
        }

        int credited = userBalanceRepository.creditAvailable(
                treasuryUserId, b.getTokenId(), fee);
        if (credited == 0) {
            // First fee for this token to the treasury account — row
            // doesn't exist yet. Create it once + retry credit.
            UserBalance treasury = new UserBalance();
            treasury.setUserId(treasuryUserId);
            treasury.setTokenId(b.getTokenId());
            treasury.setAvailable(0);
            treasury.setLocked(0);
            userBalanceRepository.save(treasury);
            userBalanceRepository.creditAvailable(treasuryUserId, b.getTokenId(), fee);
        }

        // Mark accrual via dedicated UPDATE — NOT via b.save(), which
        // would write back the stale in-memory `available` and silently
        // undo the deduct we just made. Caught in Sprint 3 day 3
        // smoke-test: ivanov's SBER showed unchanged despite a
        // CustodyFeeAccrued event being published.
        userBalanceRepository.markCustodyAccrued(b.getUserId(), b.getTokenId(), accruedThrough);

        String symbol = tokenRepository.findById(b.getTokenId())
                .map(t -> t.getSymbol())
                .orElse("UNKNOWN");
        outbox.append("balance",
                b.getUserId() + ":" + b.getTokenId(),
                "CustodyFeeAccrued",
                "token-events",
                new CustodyFeeAccruedEvent(
                        b.getUserId(), b.getTokenId(), symbol,
                        fee, days, custodyBpsPerAnnum, now));

        return fee;
    }

    /** Audit payload for a custody-fee accrual. */
    public record CustodyFeeAccruedEvent(
            UUID userId,
            UUID tokenId,
            String symbol,
            long feeAmount,
            long daysAccrued,
            int bpsPerAnnum,
            LocalDateTime occurredAt
    ) {}
}
