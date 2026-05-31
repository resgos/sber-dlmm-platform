package com.sber.dlmm.pool.service;

import com.sber.dlmm.common.outbox.OutboxService;
import com.sber.dlmm.pool.entity.LimitOrder;
import com.sber.dlmm.pool.entity.LimitOrderSide;
import com.sber.dlmm.pool.entity.LimitOrderStatus;
import com.sber.dlmm.pool.repository.LimitOrderRepository;
import com.sber.dlmm.pool.repository.LiquidityPoolRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LimitOrderFillTest {

    @Mock LimitOrderRepository orderRepository;
    @Mock LimitOrderBalanceWriter balanceWriter;
    @Mock OutboxService outbox;
    @InjectMocks LimitOrderFiller filler;

    private static final UUID USER = UUID.randomUUID();
    private static final UUID TOKEN_X = UUID.randomUUID();
    private static final UUID TOKEN_Y = UUID.randomUUID();

    private LimitOrder openSell() {
        return LimitOrder.builder()
                .id(UUID.randomUUID()).userId(USER).poolId(UUID.randomUUID()).side(LimitOrderSide.SELL)
                .tokenInId(TOKEN_X).tokenOutId(TOKEN_Y).amountIn(5_000)
                .limitPrice(new BigDecimal("5300000")).amountOut(26_500_000_000L)
                .status(LimitOrderStatus.OPEN).build();
    }

    // ── shouldFill (pure trigger logic) ──────────────────────────────────────

    @Test
    void sellFillsWhenPriceAtOrAboveLimit() {
        BigDecimal limit = new BigDecimal("5300000");
        assertThat(LimitOrderFillWatcher.shouldFill(LimitOrderSide.SELL, new BigDecimal("5300000"), limit)).isTrue();
        assertThat(LimitOrderFillWatcher.shouldFill(LimitOrderSide.SELL, new BigDecimal("5400000"), limit)).isTrue();
        assertThat(LimitOrderFillWatcher.shouldFill(LimitOrderSide.SELL, new BigDecimal("5299999"), limit)).isFalse();
    }

    @Test
    void buyFillsWhenPriceAtOrBelowLimit() {
        BigDecimal limit = new BigDecimal("5000000");
        assertThat(LimitOrderFillWatcher.shouldFill(LimitOrderSide.BUY, new BigDecimal("5000000"), limit)).isTrue();
        assertThat(LimitOrderFillWatcher.shouldFill(LimitOrderSide.BUY, new BigDecimal("4900000"), limit)).isTrue();
        assertThat(LimitOrderFillWatcher.shouldFill(LimitOrderSide.BUY, new BigDecimal("5000001"), limit)).isFalse();
    }

    // ── filler.fill ──────────────────────────────────────────────────────────

    @Test
    void fillCreditsOutputAndMarksFilled() {
        LimitOrder order = openSell();
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(LimitOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean filled = filler.fill(order.getId());

        assertThat(filled).isTrue();
        verify(balanceWriter).credit(USER, TOKEN_Y, 26_500_000_000L);
        assertThat(order.getStatus()).isEqualTo(LimitOrderStatus.FILLED);
        assertThat(order.getFilledAt()).isNotNull();
        verify(outbox).append(any(), any(), org.mockito.ArgumentMatchers.eq("LimitOrderFilled"), any(), any());
    }

    @Test
    void fillIsNoOpWhenOrderAlreadyClosed() {
        LimitOrder order = openSell();
        order.setStatus(LimitOrderStatus.CANCELLED);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        boolean filled = filler.fill(order.getId());

        assertThat(filled).isFalse();
        verify(balanceWriter, never()).credit(any(), any(), anyLong());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void fillIsNoOpWhenOrderMissing() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(filler.fill(id)).isFalse();
        verify(balanceWriter, never()).credit(any(), any(), anyLong());
    }
}
