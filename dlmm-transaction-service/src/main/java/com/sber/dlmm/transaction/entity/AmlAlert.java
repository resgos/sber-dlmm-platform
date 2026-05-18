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

    public enum Pattern {
        /** ≥3 identical amounts within 1h — structuring proxy. */
        ROUND_AMOUNT_REPEATS,
        /** Deposit → withdrawal within 5min, ≥80% of deposit. */
        FAST_IN_FAST_OUT,
        /** 24h aggregate just under the 115-ФЗ 600k ₽ reporting threshold. */
        SUB_THRESHOLD_SPLIT
    }

    public enum Severity { LOW, MEDIUM, HIGH }

    public enum ReviewOutcome { PENDING, FALSE_POSITIVE, ESCALATED_TO_ROSFINMONITORING, RESOLVED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Pattern pattern;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Severity severity;

    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    @Column(name = "window_start", nullable = false)
    private LocalDateTime windowStart;

    @Column(name = "window_end", nullable = false)
    private LocalDateTime windowEnd;

    @Column(name = "transaction_count", nullable = false)
    private int transactionCount;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Column(name = "evidence_json", columnDefinition = "text")
    private String evidenceJson;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_outcome", length = 40)
    private ReviewOutcome reviewOutcome;

    @PrePersist
    void onCreate() {
        if (detectedAt == null) detectedAt = LocalDateTime.now();
        if (severity == null) severity = Severity.MEDIUM;
        if (reviewOutcome == null) reviewOutcome = ReviewOutcome.PENDING;
    }
}
