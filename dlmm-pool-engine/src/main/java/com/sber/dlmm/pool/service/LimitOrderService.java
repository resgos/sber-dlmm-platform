package com.sber.dlmm.pool.service;

import com.sber.dlmm.common.enums.PoolStatus;
import com.sber.dlmm.common.exception.ForbiddenException;
import com.sber.dlmm.common.exception.LimitOrderException;
import com.sber.dlmm.common.exception.PoolNotActiveException;
import com.sber.dlmm.common.exception.PoolNotFoundException;
import com.sber.dlmm.common.exception.UserSelfRestrictedException;
import com.sber.dlmm.common.outbox.OutboxService;
import com.sber.dlmm.pool.client.TokenServiceClient;
import com.sber.dlmm.pool.client.TokenServiceClient.TokenInfo;
import com.sber.dlmm.pool.client.UserServiceClient;
import com.sber.dlmm.pool.dto.CreateLimitOrderRequest;
import com.sber.dlmm.pool.dto.LimitOrderResponse;
import com.sber.dlmm.pool.entity.LimitOrder;
import com.sber.dlmm.pool.entity.LimitOrderSide;
import com.sber.dlmm.pool.entity.LimitOrderStatus;
import com.sber.dlmm.pool.entity.LiquidityPool;
import com.sber.dlmm.pool.event.LimitOrderCancelledEvent;
import com.sber.dlmm.pool.event.LimitOrderPlacedEvent;
import com.sber.dlmm.pool.repository.LimitOrderRepository;
import com.sber.dlmm.pool.repository.LiquidityPoolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Sprint 16 (Meteora parity) — DLMM limit orders, escrow-settled.
 *
 * <p>Placement escrows the input token (deduct); the {@code LimitOrderFiller}
 * watcher settles at the exact limit price when the pool's market-synced price
 * crosses the trigger; cancellation refunds. Pool reserves/bins are never
 * touched, so this rides alongside the swap engine without affecting its math.
 *
 * <p>The dual-write window (a successful balance HTTP call followed by a failed
 * commit) is the same one {@code LiquidityService} accepts for add/remove
 * liquidity — see TokenServiceClient's non-idempotent deduct/credit note.
 */
@Service
public class LimitOrderService {

    private static final Logger log = LoggerFactory.getLogger(LimitOrderService.class);
    private static final String POOL_EVENTS_TOPIC = "pool-events";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal MAX_LONG = BigDecimal.valueOf(Long.MAX_VALUE);

    private final LiquidityPoolRepository poolRepository;
    private final LimitOrderRepository orderRepository;
    private final TokenServiceClient tokenServiceClient;
    private final UserServiceClient userServiceClient;
    private final LimitOrderBalanceWriter balanceWriter;
    private final OutboxService outbox;

    public LimitOrderService(LiquidityPoolRepository poolRepository,
                             LimitOrderRepository orderRepository,
                             TokenServiceClient tokenServiceClient,
                             UserServiceClient userServiceClient,
                             LimitOrderBalanceWriter balanceWriter,
                             OutboxService outbox) {
        this.poolRepository = poolRepository;
        this.orderRepository = orderRepository;
        this.tokenServiceClient = tokenServiceClient;
        this.userServiceClient = userServiceClient;
        this.balanceWriter = balanceWriter;
        this.outbox = outbox;
    }

    @Transactional
    public LimitOrderResponse createLimitOrder(CreateLimitOrderRequest req, UUID userId) {
        // Idempotency: a repeat with the same key returns the already-placed order.
        String idem = blankToNull(req.idempotencyKey());
        if (idem != null) {
            Optional<LimitOrder> existing = orderRepository.findByIdempotencyKey(idem);
            if (existing.isPresent()) {
                LimitOrder o = existing.get();
                if (!o.getUserId().equals(userId)) {
                    throw new ForbiddenException("Idempotency key belongs to another user");
                }
                return toResponse(o);
            }
        }

        LiquidityPool pool = poolRepository.findById(req.poolId())
                .orElseThrow(() -> new PoolNotFoundException("Pool not found: " + req.poolId()));
        if (pool.getStatus() != PoolStatus.ACTIVE) {
            throw new PoolNotActiveException("Pool is not active: " + pool.getStatus());
        }
        if (!userServiceClient.isUserKycVerified(userId)) {
            throw new ForbiddenException("User KYC not verified");
        }
        if (userServiceClient.isUserSelfRestricted(userId)) {
            throw new UserSelfRestrictedException("User is self-restricted from opening new positions");
        }

        BigDecimal price = req.limitPrice();
        if (price == null || price.signum() <= 0) {
            throw new LimitOrderException("limitPrice must be positive");
        }
        long amountIn = req.amountIn();
        if (amountIn < 1) {
            throw new LimitOrderException("amountIn must be >= 1");
        }

        // SELL escrows X, pays Y = in·price; BUY escrows Y, pays X = in/price.
        UUID tokenInId;
        UUID tokenOutId;
        if (req.side() == LimitOrderSide.SELL) {
            tokenInId = pool.getTokenXId();
            tokenOutId = pool.getTokenYId();
        } else {
            tokenInId = pool.getTokenYId();
            tokenOutId = pool.getTokenXId();
        }
        long amountOut = computeAmountOut(req.side(), amountIn, price);

        // Escrow the input. Non-idempotent + no retry: on failure the @Transactional
        // rolls back and no order row is persisted (see TokenServiceClient).
        tokenServiceClient.deductBalance(userId, tokenInId, amountIn);

        LimitOrder order = LimitOrder.builder()
                .userId(userId)
                .poolId(pool.getId())
                .side(req.side())
                .tokenInId(tokenInId)
                .tokenOutId(tokenOutId)
                .amountIn(amountIn)
                .limitPrice(price)
                .amountOut(amountOut)
                .status(LimitOrderStatus.OPEN)
                .idempotencyKey(idem)
                .build();
        order = orderRepository.save(order);

        outbox.append("limitorder", order.getId().toString(), "LimitOrderPlaced", POOL_EVENTS_TOPIC,
                new LimitOrderPlacedEvent(pool.getId(), userId, order.getId(),
                        req.side().name(), amountIn, price));

        log.info("Limit order placed: user={} pool={} side={} in={} price={} -> out={}",
                userId, pool.getId(), req.side(), amountIn, price, amountOut);
        return toResponse(order);
    }

    @Transactional
    public LimitOrderResponse cancelLimitOrder(UUID orderId, UUID userId) {
        LimitOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> LimitOrderException.notFound(orderId));
        if (!order.getUserId().equals(userId)) {
            throw new ForbiddenException("Limit order does not belong to user");
        }
        if (order.getStatus() != LimitOrderStatus.OPEN) {
            throw new LimitOrderException("Limit order is not open: " + order.getStatus());
        }

        // Refund the escrow in-transaction (atomic with the status change below,
        // so the @Version guard covers it). If a watcher fill commits first,
        // saveAndFlush trips the optimistic lock and the refund rolls back.
        balanceWriter.credit(userId, order.getTokenInId(), order.getAmountIn());

        order.setStatus(LimitOrderStatus.CANCELLED);
        order.setCancelledAt(LocalDateTime.now());
        try {
            orderRepository.saveAndFlush(order);
        } catch (OptimisticLockingFailureException e) {
            throw new LimitOrderException("Order is no longer open (it just filled)");
        }

        outbox.append("limitorder", order.getId().toString(), "LimitOrderCancelled", POOL_EVENTS_TOPIC,
                new LimitOrderCancelledEvent(order.getPoolId(), userId, order.getId(),
                        order.getTokenInId(), order.getAmountIn()));
        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public List<LimitOrderResponse> getUserOrders(UUID userId, LimitOrderStatus status) {
        List<LimitOrder> orders = (status == null)
                ? orderRepository.findByUserIdOrderByCreatedAtDesc(userId)
                : orderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status);
        return toResponseList(orders);
    }

    @Transactional(readOnly = true)
    public List<LimitOrderResponse> getUserPoolOrders(UUID userId, UUID poolId) {
        return toResponseList(orderRepository.findByUserIdAndPoolIdOrderByCreatedAtDesc(userId, poolId));
    }

    /**
     * Output amount (raw units) a fill would credit, floored. Package-private +
     * static so the watcher and tests share one definition. Throws if the order
     * would round to zero output or overflow a long.
     */
    static long computeAmountOut(LimitOrderSide side, long amountIn, BigDecimal price) {
        BigDecimal out = (side == LimitOrderSide.SELL)
                ? BigDecimal.valueOf(amountIn).multiply(price, MC)        // Y = X · price
                : BigDecimal.valueOf(amountIn).divide(price, MC);         // X = Y / price
        BigDecimal floored = out.setScale(0, RoundingMode.FLOOR);
        if (floored.compareTo(BigDecimal.ONE) < 0) {
            throw new LimitOrderException("Order output rounds to zero — increase the amount or adjust the price");
        }
        if (floored.compareTo(MAX_LONG) > 0) {
            throw new LimitOrderException("Order is too large");
        }
        return floored.longValueExact();
    }

    private LimitOrderResponse toResponse(LimitOrder o) {
        Map<UUID, TokenInfo> tokens = tokenServiceClient.getTokensByIds(
                List.of(o.getTokenInId(), o.getTokenOutId()));
        return LimitOrderResponse.of(o, symbol(tokens, o.getTokenInId()), symbol(tokens, o.getTokenOutId()));
    }

    private List<LimitOrderResponse> toResponseList(List<LimitOrder> orders) {
        if (orders.isEmpty()) return List.of();
        Set<UUID> ids = new HashSet<>(orders.size() * 2);
        for (LimitOrder o : orders) {
            ids.add(o.getTokenInId());
            ids.add(o.getTokenOutId());
        }
        Map<UUID, TokenInfo> tokens = tokenServiceClient.getTokensByIds(ids);
        return orders.stream()
                .map(o -> LimitOrderResponse.of(o, symbol(tokens, o.getTokenInId()), symbol(tokens, o.getTokenOutId())))
                .toList();
    }

    private static String symbol(Map<UUID, TokenInfo> tokens, UUID id) {
        TokenInfo t = tokens.get(id);
        return t == null ? null : t.symbol();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
