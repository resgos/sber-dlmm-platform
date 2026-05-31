package com.sber.dlmm.pool.service;

import com.sber.dlmm.common.enums.PoolStatus;
import com.sber.dlmm.common.exception.ForbiddenException;
import com.sber.dlmm.common.exception.LimitOrderException;
import com.sber.dlmm.common.outbox.OutboxService;
import com.sber.dlmm.pool.client.TokenServiceClient;
import com.sber.dlmm.pool.client.UserServiceClient;
import com.sber.dlmm.pool.dto.CreateLimitOrderRequest;
import com.sber.dlmm.pool.dto.LimitOrderResponse;
import com.sber.dlmm.pool.entity.LimitOrder;
import com.sber.dlmm.pool.entity.LimitOrderSide;
import com.sber.dlmm.pool.entity.LimitOrderStatus;
import com.sber.dlmm.pool.entity.LiquidityPool;
import com.sber.dlmm.pool.repository.LimitOrderRepository;
import com.sber.dlmm.pool.repository.LiquidityPoolRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LimitOrderServiceTest {

    @Mock LiquidityPoolRepository poolRepository;
    @Mock LimitOrderRepository orderRepository;
    @Mock TokenServiceClient tokenServiceClient;
    @Mock UserServiceClient userServiceClient;
    @Mock LimitOrderBalanceWriter balanceWriter;
    @Mock OutboxService outbox;
    @InjectMocks LimitOrderService service;

    private static final UUID USER = UUID.randomUUID();
    private static final UUID POOL = UUID.randomUUID();
    private static final UUID TOKEN_X = UUID.randomUUID();
    private static final UUID TOKEN_Y = UUID.randomUUID();

    private LiquidityPool activePool() {
        return LiquidityPool.builder()
                .id(POOL).tokenXId(TOKEN_X).tokenYId(TOKEN_Y)
                .status(PoolStatus.ACTIVE).basePrice(new BigDecimal("5000000"))
                .build();
    }

    // ── computeAmountOut (pure) ──────────────────────────────────────────────

    @Test
    void sellMultipliesAmountByPrice() {
        // 5000 raw X (0.5 X) at 5_300_000 → 26.5e9 raw Y
        assertThat(LimitOrderService.computeAmountOut(LimitOrderSide.SELL, 5_000, new BigDecimal("5300000")))
                .isEqualTo(26_500_000_000L);
    }

    @Test
    void buyDividesAmountByPrice() {
        // 26.5e9 raw Y at 5_300_000 → 5000 raw X
        assertThat(LimitOrderService.computeAmountOut(LimitOrderSide.BUY, 26_500_000_000L, new BigDecimal("5300000")))
                .isEqualTo(5_000L);
    }

    @Test
    void computeAmountOutFloorsDown() {
        // 3 raw Y / 2.0 = 1.5 → floor 1
        assertThat(LimitOrderService.computeAmountOut(LimitOrderSide.BUY, 3, new BigDecimal("2")))
                .isEqualTo(1L);
    }

    @Test
    void rejectsOrderThatRoundsToZeroOutput() {
        // 1 raw Y / 5_000_000 → 0
        assertThatThrownBy(() -> LimitOrderService.computeAmountOut(LimitOrderSide.BUY, 1, new BigDecimal("5000000")))
                .isInstanceOf(LimitOrderException.class);
    }

    @Test
    void rejectsOrderThatOverflowsLong() {
        assertThatThrownBy(() -> LimitOrderService.computeAmountOut(
                LimitOrderSide.SELL, Long.MAX_VALUE, new BigDecimal("1000")))
                .isInstanceOf(LimitOrderException.class);
    }

    // ── createLimitOrder ─────────────────────────────────────────────────────

    @Test
    void createSellEscrowsTokenXAndPersistsOpenOrder() {
        when(poolRepository.findById(POOL)).thenReturn(Optional.of(activePool()));
        when(userServiceClient.isUserKycVerified(USER)).thenReturn(true);
        when(userServiceClient.isUserSelfRestricted(USER)).thenReturn(false);
        when(orderRepository.save(any(LimitOrder.class))).thenAnswer(inv -> {
            LimitOrder o = inv.getArgument(0); // simulate JPA @PrePersist id assignment
            if (o.getId() == null) o.setId(UUID.randomUUID());
            return o;
        });
        when(tokenServiceClient.getTokensByIds(any())).thenReturn(Map.of());

        CreateLimitOrderRequest req = new CreateLimitOrderRequest(
                POOL, LimitOrderSide.SELL, 5_000, new BigDecimal("5300000"), null);
        LimitOrderResponse resp = service.createLimitOrder(req, USER);

        // Escrow = the input token (X) for a SELL.
        verify(tokenServiceClient).deductBalance(USER, TOKEN_X, 5_000);

        ArgumentCaptor<LimitOrder> saved = ArgumentCaptor.forClass(LimitOrder.class);
        verify(orderRepository).save(saved.capture());
        LimitOrder o = saved.getValue();
        assertThat(o.getStatus()).isEqualTo(LimitOrderStatus.OPEN);
        assertThat(o.getTokenInId()).isEqualTo(TOKEN_X);
        assertThat(o.getTokenOutId()).isEqualTo(TOKEN_Y);
        assertThat(o.getAmountOut()).isEqualTo(26_500_000_000L);
        assertThat(resp.side()).isEqualTo(LimitOrderSide.SELL);
        verify(outbox).append(eq("limitorder"), any(), eq("LimitOrderPlaced"), any(), any());
    }

    @Test
    void createBuyEscrowsTokenY() {
        when(poolRepository.findById(POOL)).thenReturn(Optional.of(activePool()));
        when(userServiceClient.isUserKycVerified(USER)).thenReturn(true);
        when(userServiceClient.isUserSelfRestricted(USER)).thenReturn(false);
        when(orderRepository.save(any(LimitOrder.class))).thenAnswer(inv -> {
            LimitOrder o = inv.getArgument(0); // simulate JPA @PrePersist id assignment
            if (o.getId() == null) o.setId(UUID.randomUUID());
            return o;
        });
        when(tokenServiceClient.getTokensByIds(any())).thenReturn(Map.of());

        CreateLimitOrderRequest req = new CreateLimitOrderRequest(
                POOL, LimitOrderSide.BUY, 26_500_000_000L, new BigDecimal("5300000"), null);
        service.createLimitOrder(req, USER);

        verify(tokenServiceClient).deductBalance(USER, TOKEN_Y, 26_500_000_000L);
    }

    @Test
    void createRejectedWhenKycNotVerified() {
        when(poolRepository.findById(POOL)).thenReturn(Optional.of(activePool()));
        when(userServiceClient.isUserKycVerified(USER)).thenReturn(false);

        CreateLimitOrderRequest req = new CreateLimitOrderRequest(
                POOL, LimitOrderSide.SELL, 5_000, new BigDecimal("5300000"), null);
        assertThatThrownBy(() -> service.createLimitOrder(req, USER))
                .isInstanceOf(ForbiddenException.class);

        verify(tokenServiceClient, never()).deductBalance(any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void createWithKnownIdempotencyKeyReturnsExistingOrderWithoutEscrow() {
        LimitOrder existing = LimitOrder.builder()
                .id(UUID.randomUUID()).userId(USER).poolId(POOL).side(LimitOrderSide.SELL)
                .tokenInId(TOKEN_X).tokenOutId(TOKEN_Y).amountIn(5_000)
                .limitPrice(new BigDecimal("5300000")).amountOut(26_500_000_000L)
                .status(LimitOrderStatus.OPEN).idempotencyKey("dup-1").build();
        when(orderRepository.findByIdempotencyKey("dup-1")).thenReturn(Optional.of(existing));
        when(tokenServiceClient.getTokensByIds(any())).thenReturn(Map.of());

        CreateLimitOrderRequest req = new CreateLimitOrderRequest(
                POOL, LimitOrderSide.SELL, 5_000, new BigDecimal("5300000"), "dup-1");
        service.createLimitOrder(req, USER);

        verify(tokenServiceClient, never()).deductBalance(any(), any(), org.mockito.ArgumentMatchers.anyLong());
        verify(orderRepository, never()).save(any());
    }

    // ── cancelLimitOrder ─────────────────────────────────────────────────────

    @Test
    void cancelRefundsEscrowAndMarksCancelled() {
        LimitOrder order = LimitOrder.builder()
                .id(UUID.randomUUID()).userId(USER).poolId(POOL).side(LimitOrderSide.SELL)
                .tokenInId(TOKEN_X).tokenOutId(TOKEN_Y).amountIn(5_000)
                .limitPrice(new BigDecimal("5300000")).amountOut(26_500_000_000L)
                .status(LimitOrderStatus.OPEN).build();
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(LimitOrder.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tokenServiceClient.getTokensByIds(any())).thenReturn(Map.of());

        service.cancelLimitOrder(order.getId(), USER);

        verify(balanceWriter).credit(USER, TOKEN_X, 5_000);
        assertThat(order.getStatus()).isEqualTo(LimitOrderStatus.CANCELLED);
        assertThat(order.getCancelledAt()).isNotNull();
    }

    @Test
    void cancelRejectedForNonOwner() {
        LimitOrder order = LimitOrder.builder()
                .id(UUID.randomUUID()).userId(UUID.randomUUID()).poolId(POOL).side(LimitOrderSide.SELL)
                .tokenInId(TOKEN_X).tokenOutId(TOKEN_Y).amountIn(5_000)
                .limitPrice(new BigDecimal("5300000")).amountOut(1).status(LimitOrderStatus.OPEN).build();
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancelLimitOrder(order.getId(), USER))
                .isInstanceOf(ForbiddenException.class);
        verify(balanceWriter, never()).credit(any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void cancelRejectedWhenNotOpen() {
        LimitOrder order = LimitOrder.builder()
                .id(UUID.randomUUID()).userId(USER).poolId(POOL).side(LimitOrderSide.SELL)
                .tokenInId(TOKEN_X).tokenOutId(TOKEN_Y).amountIn(5_000)
                .limitPrice(new BigDecimal("5300000")).amountOut(1).status(LimitOrderStatus.FILLED).build();
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancelLimitOrder(order.getId(), USER))
                .isInstanceOf(LimitOrderException.class);
    }
}
