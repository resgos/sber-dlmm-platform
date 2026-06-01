package com.sber.dlmm.transaction.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sprint 6 #6.9 — AML pattern-detection alert. Persisted by
 * {@code AmlScannerScheduler} when a {@link Pattern} fires on recent
 * transactions of a user.
 *
 * <p>Lifecycle: {@code review_outcome} starts PENDING; compliance
 * reviewer flips to FALSE_POSITIVE / ESCALATED_TO_ROSFINMONITORING /
 * RESOLVED via admin endpoint (Sprint 7+ admin-ui).
 */
@Entity
@Table(name = "aml_alerts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AmlAlert {

    /** The behavioural pattern that tripped the scanner and produced this alert. */
    public enum Pattern {
        /** ≥3 identical amounts within 1h — structuring proxy. */
        ROUND_AMOUNT_REPEATS,
        /** Deposit → withdrawal within 5min, ≥80% of deposit. */
        FAST_IN_FAST_OUT,
        /** 24h aggregate just under the 115-ФЗ 600k ₽ reporting threshold. */
        SUB_THRESHOLD_SPLIT
    }

    /** Risk weight assigned to the alert; drives triage ordering in the admin UI. */
    public enum Severity { LOW, MEDIUM, HIGH }

    /**
     * Compliance disposition. Starts PENDING; a reviewer flips it to one
     * of the terminal outcomes (dismissed as false positive, escalated to
     * Rosfinmonitoring, or otherwise resolved).
     */
    public enum ReviewOutcome { PENDING, FALSE_POSITIVE, ESCALATED_TO_ROSFINMONITORING, RESOLVED }

    /** Surrogate primary key (server-generated UUID). */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** User whose activity triggered the alert. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Which detection pattern fired. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Pattern pattern;

    /** Risk weight; defaults to MEDIUM on insert. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity severity;

    /** When the scanner raised the alert; defaulted by {@link #onCreate()}. */
    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    /** Inclusive start of the observation window the pattern matched over. */
    @Column(name = "window_start", nullable = false)
    private LocalDateTime windowStart;

    /** End of the observation window the pattern matched over. */
    @Column(name = "window_end", nullable = false)
    private LocalDateTime windowEnd;

    /** Number of transactions contributing to the match. */
    @Column(name = "transaction_count", nullable = false)
    private int transactionCount;

    /** Aggregate amount across the matched transactions, raw ×10⁴ scale. */
    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    /** JSON snapshot of the supporting evidence (e.g. the offending tx ids). */
    @Column(name = "evidence_json", columnDefinition = "text")
    private String evidenceJson;

    /** Compliance reviewer who dispositioned the alert; paired with {@link #reviewedAt}. */
    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    /** When the alert was dispositioned; null while PENDING. */
    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /** Compliance disposition; defaults to PENDING on insert. */
    @Enumerated(EnumType.STRING)
    @Column(name = "review_outcome", length = 40)
    private ReviewOutcome reviewOutcome;

    /**
     * JPA pre-insert hook: defaults {@link #detectedAt} to now,
     * {@link #severity} to MEDIUM and {@link #reviewOutcome} to PENDING
     * when they are not already set.
     */
    @PrePersist
    void onCreate() {
        if (detectedAt == null) detectedAt = LocalDateTime.now();
        if (severity == null) severity = Severity.MEDIUM;
        if (reviewOutcome == null) reviewOutcome = ReviewOutcome.PENDING;
    }
}
