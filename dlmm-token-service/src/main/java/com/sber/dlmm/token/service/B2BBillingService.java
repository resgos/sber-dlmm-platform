package com.sber.dlmm.token.service;

import com.sber.dlmm.token.entity.B2BInvoice;
import com.sber.dlmm.token.entity.B2BIssuer;
import com.sber.dlmm.token.repository.B2BInvoiceRepository;
import com.sber.dlmm.token.repository.B2BIssuerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sprint 5 #5.7 — monthly billing for B2B issuers.
 *
 * <p>Scheduled monthly: on the 1st of each month at 02:00 server time,
 * generates an invoice for period N-1 for every KYB-APPROVED issuer.
 * The unique constraint on (issuer_id, period_start) makes re-runs safe
 * (existing rows are skipped — no double-billing).
 *
 * <p>Pricing per tier (configurable):
 * <pre>
 *   BASIC      → listing 100k SRUB, retainer 50k SRUB/month, volume fee 5 bps
 *   PRO        → listing 500k SRUB, retainer 200k SRUB/month, volume fee 3 bps
 *   ENTERPRISE → listing 2M SRUB,   retainer 1M SRUB/month,   volume fee 1 bp
 * </pre>
 *
 * <p>Volume is stubbed to 0 in the prototype (issuer-token volume
 * aggregation requires joining pool-engine volume_24h across all pools
 * holding tokens minted by this issuer; tracked as Sprint 6+).
 *
 * <p>НДС split mirrors #5.12 b2b_settlements: "fee includes НДС"
 * convention, vat = gross × 20 / 120 (floor), net = gross - vat.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class B2BBillingService {

    private final B2BIssuerRepository issuerRepository;
    private final B2BInvoiceRepository invoiceRepository;

    @Value("${dlmm.b2b.billing.vat-rate-pct:20}")
    private short vatRatePct;

    @Value("${dlmm.b2b.billing.basic.listing-fee:100000}")
    private long basicListingFee;
    @Value("${dlmm.b2b.billing.basic.retainer-fee:50000}")
    private long basicRetainerFee;
    @Value("${dlmm.b2b.billing.basic.volume-fee-bps:5}")
    private int basicVolumeFeeBps;

    @Value("${dlmm.b2b.billing.pro.listing-fee:500000}")
    private long proListingFee;
    @Value("${dlmm.b2b.billing.pro.retainer-fee:200000}")
    private long proRetainerFee;
    @Value("${dlmm.b2b.billing.pro.volume-fee-bps:3}")
    private int proVolumeFeeBps;

    @Value("${dlmm.b2b.billing.enterprise.listing-fee:2000000}")
    private long enterpriseListingFee;
    @Value("${dlmm.b2b.billing.enterprise.retainer-fee:1000000}")
    private long enterpriseRetainerFee;
    @Value("${dlmm.b2b.billing.enterprise.volume-fee-bps:1}")
    private int enterpriseVolumeFeeBps;

    /**
     * Monthly scheduler — runs 1st of each month at 02:00 server time.
     * Bills the previous month for every APPROVED issuer.
     */
    @Scheduled(cron = "${dlmm.b2b.billing.cron:0 0 2 1 * *}")
    public void runMonthlyBilling() {
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        generateInvoicesForPeriod(lastMonth);
    }

    /**
     * Generates invoices for the given period for all APPROVED issuers.
     * Public for manual triggering (admin endpoint) + tests.
     *
     * <p>Idempotent per {@code (issuer, periodStart)}: issuers already billed for
     * the period are skipped, so a re-run never double-bills. The one-time listing
     * fee is added only on an issuer's first-ever invoice. Prototype volume input
     * is hard-wired to 0.
     *
     * @param period the calendar month to bill
     * @return the number of invoices actually generated (skips not counted)
     */
    @Transactional
    public int generateInvoicesForPeriod(YearMonth period) {
        LocalDate periodStart = period.atDay(1);
        LocalDate periodEnd = period.atEndOfMonth();
        List<B2BIssuer> approved = issuerRepository.findByKybStatus(B2BIssuer.KybStatus.APPROVED);
        log.info("B2B billing: generating invoices for period={} approvedIssuers={}",
                period, approved.size());

        int generated = 0;
        for (B2BIssuer issuer : approved) {
            // Idempotency — skip if already billed for this period.
            if (invoiceRepository.findByIssuerIdAndPeriodStart(issuer.getId(), periodStart).isPresent()) {
                continue;
            }
            // Listing fee only on first-ever invoice for this issuer.
            boolean firstInvoice = !invoiceRepository.existsByIssuerId(issuer.getId());
            B2BInvoice invoice = computeInvoice(issuer, periodStart, periodEnd, firstInvoice, 0L);
            invoiceRepository.save(invoice);
            generated++;
            log.info("B2B invoice ISSUED issuer={} period={} gross={} vat={} net={}",
                    issuer.getId(), period, invoice.getGrossTotal(),
                    invoice.getVatAmount(), invoice.getNetTotal());
        }
        return generated;
    }

    /**
     * Pure computation — splits out for direct unit testing without JPA.
     * Visible static so tests don't need Spring context.
     *
     * <p>Builds the invoice from the issuer's tier: a one-time listing fee (first
     * invoice only) + monthly retainer + a volume fee ({@code volume × bps / 10000}).
     * The gross total is treated as VAT-inclusive, so {@code vat = gross × rate /
     * (100 + rate)} (floor) and {@code net = gross - vat} — mirroring the #5.12
     * b2b_settlements НДС convention. All money fields are raw SRUB units.
     *
     * @param issuer           issuer being billed (its tier drives every rate)
     * @param periodStart      inclusive first day of the billed period
     * @param periodEnd        inclusive last day of the billed period
     * @param firstInvoice     if true, listing fee is added
     * @param periodVolumeSrub aggregate SRUB-equivalent volume on the
     *        issuer's tokens during the period; 0 in prototype
     * @return an ISSUED invoice (unsaved) with listing/retainer/volume/gross/vat/net populated
     */
    public B2BInvoice computeInvoice(B2BIssuer issuer, LocalDate periodStart, LocalDate periodEnd,
                                       boolean firstInvoice, long periodVolumeSrub) {
        long listing = firstInvoice ? listingFeeFor(issuer.getTier()) : 0L;
        long retainer = retainerFeeFor(issuer.getTier());
        long volume = periodVolumeSrub * volumeFeeBpsFor(issuer.getTier()) / 10_000L;
        long gross = listing + retainer + volume;
        long vat = gross * vatRatePct / (100L + vatRatePct);
        long net = gross - vat;

        return B2BInvoice.builder()
                .issuerId(issuer.getId())
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .listingFee(listing)
                .retainerFee(retainer)
                .volumeFee(volume)
                .grossTotal(gross)
                .vatAmount(vat)
                .netTotal(net)
                .vatRatePct(vatRatePct)
                .status(B2BInvoice.Status.ISSUED)
                .build();
    }

    /**
     * @param t issuer tier
     * @return the one-time listing fee for the tier, in raw SRUB units
     */
    private long listingFeeFor(B2BIssuer.Tier t) {
        return switch (t) {
            case BASIC -> basicListingFee;
            case PRO -> proListingFee;
            case ENTERPRISE -> enterpriseListingFee;
        };
    }
    /**
     * @param t issuer tier
     * @return the monthly retainer fee for the tier, in raw SRUB units
     */
    private long retainerFeeFor(B2BIssuer.Tier t) {
        return switch (t) {
            case BASIC -> basicRetainerFee;
            case PRO -> proRetainerFee;
            case ENTERPRISE -> enterpriseRetainerFee;
        };
    }
    /**
     * @param t issuer tier
     * @return the per-volume fee rate for the tier, in basis points
     */
    private int volumeFeeBpsFor(B2BIssuer.Tier t) {
        return switch (t) {
            case BASIC -> basicVolumeFeeBps;
            case PRO -> proVolumeFeeBps;
            case ENTERPRISE -> enterpriseVolumeFeeBps;
        };
    }

    /**
     * @param issuerId issuer whose invoices to list
     * @return the issuer's invoices, newest billing period first
     */
    @Transactional(readOnly = true)
    public List<B2BInvoice> findByIssuer(UUID issuerId) {
        return invoiceRepository.findByIssuerIdOrderByPeriodStartDesc(issuerId);
    }
}
