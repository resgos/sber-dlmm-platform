package com.sber.dlmm.pool.service;

import com.sber.dlmm.pool.entity.LimitOrder;
import com.sber.dlmm.pool.entity.LimitOrderSide;
import com.sber.dlmm.pool.entity.LimitOrderStatus;
import com.sber.dlmm.pool.entity.LiquidityPool;
import com.sber.dlmm.pool.repository.LimitOrderRepository;
import com.sber.dlmm.pool.repository.LiquidityPoolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sprint 16 — scheduled limit-order fill watcher. Every cycle it reads the open
 * orders and each pool's current price ({@code basePrice}, which
 * {@link PoolPriceSyncService} keeps synced to the real market) and asks
 * {@link LimitOrderFiller} to settle any order the price has crossed:
 * a SELL fills once price ≥ its limit, a BUY once price ≤ its limit.
 *
 * <p>Mirrors {@link PoolPriceSyncService}: a thin scheduled sweep that delegates
 * the per-item mutation to a separate {@code @Transactional} bean. Spring's
 * default scheduler is single-threaded, so cycles never overlap.
 */
@Service
public class LimitOrderFillWatcher {

    private static final Logger log = LoggerFactory.getLogger(LimitOrderFillWatcher.class);

    private final LimitOrderRepository orderRepository;
    private final LiquidityPoolRepository poolRepository;
    private final LimitOrderFiller filler;
    private final boolean enabled;

    public LimitOrderFillWatcher(LimitOrderRepository orderRepository,
                                 LiquidityPoolRepository poolRepository,
                                 LimitOrderFiller filler,
                                 @Value("${dlmm.limit-orders.fill-enabled:true}") boolean enabled) {
        this.orderRepository = orderRepository;
        this.poolRepository = poolRepository;
        this.filler = filler;
        this.enabled = enabled;
    }

    @Scheduled(fixedRateString = "${dlmm.limit-orders.fill-check-ms:15000}", initialDelay = 20_000)
    public void checkAndFill() {
        if (!enabled) return;

        List<LimitOrder> open = orderRepository.findByStatus(LimitOrderStatus.OPEN);
        if (open.isEmpty()) return;

        Map<UUID, BigDecimal> priceByPool = new HashMap<>();
        int filled = 0;
        for (LimitOrder order : open) {
            try {
                BigDecimal price = priceByPool.computeIfAbsent(order.getPoolId(), pid ->
                        poolRepository.findById(pid).map(LiquidityPool::getBasePrice).orElse(null));
                if (price == null || price.signum() <= 0) continue;
                if (!shouldFill(order.getSide(), price, order.getLimitPrice())) continue;
                if (filler.fill(order.getId())) filled++;
            } catch (Exception e) {
                log.warn("Limit order {} fill failed, retry next cycle: {}", order.getId(), e.toString());
            }
        }
        if (filled > 0) {
            log.info("Limit orders filled this cycle: {}", filled);
        }
    }

    /** A SELL fills when the market reaches/exceeds its ask; a BUY when it reaches/falls below its bid. */
    static boolean shouldFill(LimitOrderSide side, BigDecimal currentPrice, BigDecimal limitPrice) {
        return side == LimitOrderSide.SELL
                ? currentPrice.compareTo(limitPrice) >= 0
                : currentPrice.compareTo(limitPrice) <= 0;
    }
}
