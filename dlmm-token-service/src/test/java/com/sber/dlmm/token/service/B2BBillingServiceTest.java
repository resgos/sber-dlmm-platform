package com.sber.dlmm.token.service;

import com.sber.dlmm.token.entity.B2BInvoice;
import com.sber.dlmm.token.entity.B2BIssuer;
import com.sber.dlmm.token.repository.B2BInvoiceRepository;
import com.sber.dlmm.token.repository.B2BIssuerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class B2BBillingServiceTest {

    @Mock
    private B2BIssuerRepository issuerRepository;
    @Mock
    private B2BInvoiceRepository invoiceRepository;

    @InjectMocks
    private B2BBillingService billingService;

    @BeforeEach
    void setUp() {
        // @Value fields default to the prototype price list documented in
        // B2BBillingService javadoc.
        ReflectionTestUtils.setField(billingService, "vatRatePct", (short) 20);
        ReflectionTestUtils.setField(billingService, "basicListingFee", 100_000L);
        ReflectionTestUtils.setField(billingService, "basicRetainerFee", 50_000L);
        ReflectionTestUtils.setField(billingService, "basicVolumeFeeBps", 5);
        ReflectionTestUtils.setField(billingService, "proListingFee", 500_000L);
        ReflectionTestUtils.setField(billingService, "proRetainerFee", 200_000L);
        ReflectionTestUtils.setField(billingService, "proVolumeFeeBps", 3);
        ReflectionTestUtils.setField(billingService, "enterpriseListingFee", 2_000_000L);
        ReflectionTestUtils.setField(billingService, "enterpriseRetainerFee", 1_000_000L);
        ReflectionTestUtils.setField(billingService, "enterpriseVolumeFeeBps", 1);

        lenient().when(invoiceRepository.save(any(B2BInvoice.class)))
                .thenAnswer(inv -> {
                    B2BInvoice i = inv.getArgument(0);
                    if (i.getId() == null) i.setId(UUID.randomUUID());
                    return i;
                });
    }

    private B2BIssuer issuer(B2BIssuer.Tier tier) {
        return B2BIssuer.builder()
                .id(UUID.randomUUID()).inn("1234567890").legalName("ООО Тест")
                .displayName("Test").contactEmail("a@b.ru")
                .tier(tier).kybStatus(B2BIssuer.KybStatus.APPROVED)
                .build();
    }

    @Test
    @DisplayName("BASIC first invoice: listing 100k + retainer 50k + zero volume = 150k gross")
    void basicFirstInvoice() {
        B2BIssuer iss = issuer(B2BIssuer.Tier.BASIC);
        B2BInvoice inv = billingService.computeInvoice(iss,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), true, 0L);

        assertEquals(100_000L, inv.getListingFee());
        assertEquals(50_000L, inv.getRetainerFee());
        assertEquals(0L, inv.getVolumeFee());
        assertEquals(150_000L, inv.getGrossTotal());
        // vat = 150000 * 20 / 120 = 25000
        assertEquals(25_000L, inv.getVatAmount());
        assertEquals(125_000L, inv.getNetTotal());
        assertEquals(inv.getGrossTotal(), inv.getVatAmount() + inv.getNetTotal());
    }

    @Test
    @DisplayName("BASIC second invoice: NO listing fee, retainer only = 50k gross")
    void basicSecondInvoice() {
        B2BIssuer iss = issuer(B2BIssuer.Tier.BASIC);
        B2BInvoice inv = billingService.computeInvoice(iss,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), false, 0L);
        assertEquals(0L, inv.getListingFee());
        assertEquals(50_000L, inv.getRetainerFee());
        assertEquals(50_000L, inv.getGrossTotal());
        assertEquals(8_333L, inv.getVatAmount());  // floor(50000*20/120)
        assertEquals(41_667L, inv.getNetTotal());
    }

    @Test
    @DisplayName("PRO tier: 500k listing + 200k retainer = 700k gross")
    void proFirstInvoice() {
        B2BInvoice inv = billingService.computeInvoice(issuer(B2BIssuer.Tier.PRO),
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), true, 0L);
        assertEquals(500_000L, inv.getListingFee());
        assertEquals(200_000L, inv.getRetainerFee());
        assertEquals(700_000L, inv.getGrossTotal());
    }

    @Test
    @DisplayName("ENTERPRISE tier: 2M listing + 1M retainer = 3M gross")
    void enterpriseFirstInvoice() {
        B2BInvoice inv = billingService.computeInvoice(issuer(B2BIssuer.Tier.ENTERPRISE),
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), true, 0L);
        assertEquals(2_000_000L, inv.getListingFee());
        assertEquals(1_000_000L, inv.getRetainerFee());
        assertEquals(3_000_000L, inv.getGrossTotal());
        assertEquals(500_000L, inv.getVatAmount());  // 3M * 20 / 120
        assertEquals(2_500_000L, inv.getNetTotal());
    }

    @Test
    @DisplayName("Volume fee BASIC: 100M volume × 5 bps = 50k volume fee")
    void volumeFeeBasic() {
        B2BInvoice inv = billingService.computeInvoice(issuer(B2BIssuer.Tier.BASIC),
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), false, 100_000_000L);
        // 100M × 5 / 10000 = 50_000
        assertEquals(50_000L, inv.getVolumeFee());
        // retainer 50k + volume 50k = 100k gross
        assertEquals(100_000L, inv.getGrossTotal());
    }

    @Test
    @DisplayName("Volume fee ENTERPRISE: 1bn volume × 1 bp = 100k volume fee")
    void volumeFeeEnterprise() {
        B2BInvoice inv = billingService.computeInvoice(issuer(B2BIssuer.Tier.ENTERPRISE),
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), false, 1_000_000_000L);
        // 1B × 1 / 10000 = 100_000
        assertEquals(100_000L, inv.getVolumeFee());
        // retainer 1M + volume 100k = 1.1M gross
        assertEquals(1_100_000L, inv.getGrossTotal());
    }

    @Test
    @DisplayName("generateInvoicesForPeriod skips issuers already billed for the period (idempotency)")
    void schedulerIdempotent() {
        B2BIssuer iss = issuer(B2BIssuer.Tier.BASIC);
        when(issuerRepository.findByKybStatus(B2BIssuer.KybStatus.APPROVED))
                .thenReturn(List.of(iss));
        // Existing invoice for the period — should be skipped.
        when(invoiceRepository.findByIssuerIdAndPeriodStart(iss.getId(), LocalDate.of(2026, 5, 1)))
                .thenReturn(Optional.of(B2BInvoice.builder().build()));

        int generated = billingService.generateInvoicesForPeriod(YearMonth.of(2026, 5));

        assertEquals(0, generated);
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    @DisplayName("generateInvoicesForPeriod creates invoice for each APPROVED issuer")
    void schedulerCreatesPerIssuer() {
        B2BIssuer a = issuer(B2BIssuer.Tier.BASIC);
        B2BIssuer b = issuer(B2BIssuer.Tier.PRO);
        when(issuerRepository.findByKybStatus(B2BIssuer.KybStatus.APPROVED))
                .thenReturn(List.of(a, b));
        when(invoiceRepository.findByIssuerIdAndPeriodStart(any(UUID.class), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(invoiceRepository.existsByIssuerId(any(UUID.class))).thenReturn(false);

        int generated = billingService.generateInvoicesForPeriod(YearMonth.of(2026, 5));

        assertEquals(2, generated);
        verify(invoiceRepository, times(2)).save(any(B2BInvoice.class));
    }

    @Test
    @DisplayName("PENDING/REJECTED issuers are NOT billed (only APPROVED appear in findByKybStatus(APPROVED))")
    void onlyApprovedBilled() {
        when(issuerRepository.findByKybStatus(B2BIssuer.KybStatus.APPROVED))
                .thenReturn(List.of()); // none approved

        int generated = billingService.generateInvoicesForPeriod(YearMonth.of(2026, 5));

        assertEquals(0, generated);
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    @DisplayName("Invariant: gross = vat + net for ALL tiers + first/recurring combinations")
    void invariantGrossEqualsVatPlusNet() {
        for (B2BIssuer.Tier tier : B2BIssuer.Tier.values()) {
            for (boolean firstInvoice : new boolean[] { true, false }) {
                for (long vol : new long[] { 0L, 100_000L, 1_000_000L, 100_000_000L }) {
                    B2BInvoice inv = billingService.computeInvoice(issuer(tier),
                            LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), firstInvoice, vol);
                    assertEquals(inv.getGrossTotal(), inv.getVatAmount() + inv.getNetTotal(),
                            "tier=" + tier + " first=" + firstInvoice + " vol=" + vol);
                    assertNotNull(inv.getStatus());
                    assertTrue(inv.getGrossTotal() >= 0);
                }
            }
        }
    }
}
