package com.sber.dlmm.token.fees;

import com.sber.dlmm.token.entity.UserBalance;
import com.sber.dlmm.token.outbox.OutboxService;
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

        long fee = (b.getAvailable() * (long) custodyBpsPerAnnum * days)
                / (BPS_DIVISOR * DAYS_IN_YEAR);

        if (fee <= 0) {
            // Sub-unit accrual → still mark accrued so the cron doesn't
            // re-evaluate this row every tick forever.
            b.setLastCustodyFeeAt(now);
            userBalanceRepository.save(b);
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

        b.setLastCustodyFeeAt(now);
        userBalanceRepository.save(b);

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
