package com.sber.dlmm.pool.service;

import com.sber.dlmm.common.enums.PoolStatus;
import com.sber.dlmm.pool.entity.LiquidityPool;
import com.sber.dlmm.pool.entity.PoolBin;
import com.sber.dlmm.pool.repository.LiquidityPoolRepository;
import com.sber.dlmm.pool.repository.PoolBinRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PoolRepricerTest {

    @Mock LiquidityPoolRepository poolRepository;
    @Mock PoolBinRepository poolBinRepository;
    @InjectMocks PoolRepricer repricer;

    @Test
    void repricesBaseAndBinLadderToTargetAndPreservesInvariant() {
        UUID pid = UUID.randomUUID();
        LiquidityPool pool = LiquidityPool.builder()
                .id(pid).status(PoolStatus.ACTIVE)
                .basePrice(new BigDecimal("5000000")).binStep(10).activeBinId(8_388_608)
                .build();
        // active bin: 2 SBTC-units + 10,000,000 SRUB at the old anchor price
        PoolBin bin = PoolBin.builder()
                .poolId(pid).binId(8_388_608)
                .price(new BigDecimal("5000000")).reserveX(2L).reserveY(10_000_000L)
                .build();
        when(poolRepository.findById(pid)).thenReturn(Optional.of(pool));
        when(poolBinRepository.findByPoolId(pid)).thenReturn(List.of(bin));

        boolean changed = repricer.reprice(pid, new BigDecimal("5240000")); // +4.8%

        assertTrue(changed);
        assertEquals(0, new BigDecimal("5240000").compareTo(pool.getBasePrice()), "base repriced to target");
        assertEquals(0, new BigDecimal("5240000").compareTo(bin.getPrice()), "bin price scaled by the same factor");
        // F-12 invariant: liquidity = reserveX·price + reserveY = 2*5240000 + 10000000 = 20480000
        assertEquals(20_480_000L, bin.getLiquidity());
        verify(poolBinRepository).saveAll(anyList());
        verify(poolRepository).save(pool);
    }

    @Test
    void skipsInactivePool() {
        UUID pid = UUID.randomUUID();
        LiquidityPool paused = LiquidityPool.builder()
                .id(pid).status(PoolStatus.PAUSED).basePrice(BigDecimal.TEN).build();
        when(poolRepository.findById(pid)).thenReturn(Optional.of(paused));

        assertFalse(repricer.reprice(pid, BigDecimal.ONE));
        verify(poolRepository, never()).save(any());
    }

    @Test
    void targetPrice_srubQuoted_isRubPriceOfX() {
        Map<String, BigDecimal> prices = Map.of("SBTC", new BigDecimal("5240000"), "SRUB", BigDecimal.ONE);
        assertEquals(0, new BigDecimal("5240000").compareTo(
                PoolPriceSyncService.targetPrice("SBTC", "SRUB", prices)));
    }

    @Test
    void targetPrice_srubBase_isReciprocal() {
        Map<String, BigDecimal> prices = Map.of("SBTC", new BigDecimal("5000000"));
        // SRUB/SBTC pool: 1 SRUB = 1/5,000,000 SBTC
        BigDecimal t = PoolPriceSyncService.targetPrice("SRUB", "SBTC", prices);
        assertEquals(0, new BigDecimal("0.0000002").compareTo(t));
    }

    @Test
    void targetPrice_cross_isRubRatio() {
        Map<String, BigDecimal> prices = Map.of("SBTC", new BigDecimal("5000000"), "SETH", new BigDecimal("250000"));
        // SBTC/SETH = 5,000,000 / 250,000 = 20
        assertEquals(0, new BigDecimal("20").compareTo(
                PoolPriceSyncService.targetPrice("SBTC", "SETH", prices)));
    }

    @Test
    void targetPrice_missingSymbolOrPrice_isNull() {
        Map<String, BigDecimal> prices = Map.of("SBTC", new BigDecimal("5000000"));
        assertNull(PoolPriceSyncService.targetPrice(null, "SRUB", prices));
        assertNull(PoolPriceSyncService.targetPrice("UNKNOWN", "SRUB", prices)); // no oracle price
    }
}
