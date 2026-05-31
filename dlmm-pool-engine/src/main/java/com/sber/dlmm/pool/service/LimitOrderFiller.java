package com.sber.dlmm.pool.service;

import com.sber.dlmm.common.outbox.OutboxService;
import com.sber.dlmm.pool.entity.LimitOrder;
import com.sber.dlmm.pool.entity.LimitOrderStatus;
import com.sber.dlmm.pool.event.LimitOrderFilledEvent;
import com.sber.dlmm.pool.repository.LimitOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Settles ONE limit order in its OWN transaction — separate bean so Spring's
 * proxy actually applies {@code @Transactional} when called from the
 * {@link LimitOrderFillWatcher} loop (self-invocation wouldn't), mirroring the
 * {@link PoolPriceSyncService} → {@link PoolRepricer} split.
 */
@Service
public class LimitOrderFiller {

    private static final Logger log = LoggerFactory.getLogger(LimitOrderFiller.class);
    private static final String POOL_EVENTS_TOPIC = "pool-events";

    private final LimitOrderRepository orderRepository;
    private final LimitOrderBalanceWriter balanceWriter;
    private final OutboxService outbox;

    public LimitOrderFiller(LimitOrderRepository orderRepository,
                            LimitOrderBalanceWriter balanceWriter,
                            OutboxService outbox) {
        this.orderRepository = orderRepository;
        this.balanceWriter = balanceWriter;
        this.outbox = outbox;
    }

    /**
     * Credit the (pre-computed) output at the limit price and mark the order
     * FILLED. Re-loads + re-checks OPEN so a cancel that raced in between is a
     * no-op. Credit happens inside the tx: a failure rolls the fill back and the
     * order is retried next cycle.
     *
     * @return true if this call filled the order; false if it was already gone /
     *         not OPEN.
     */
    @Transactional
    public boolean fill(UUID orderId) {
        LimitOrder order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.getStatus() != LimitOrderStatus.OPEN) {
            return false;
        }

        // Credit in-transaction (see LimitOrderBalanceWriter): no JWT needed on
        // the scheduler thread, and the @Version save below now also guards this
        // payout — a lost optimistic-lock race rolls the credit back with it.
        balanceWriter.credit(order.getUserId(), order.getTokenOutId(), order.getAmountOut());

        order.setStatus(LimitOrderStatus.FILLED);
        order.setFilledAt(LocalDateTime.now());
        orderRepository.save(order);

        outbox.append("limitorder", order.getId().toString(), "LimitOrderFilled", POOL_EVENTS_TOPIC,
                new LimitOrderFilledEvent(order.getPoolId(), order.getUserId(), order.getId(),
                        order.getTokenOutId(), order.getAmountOut(), order.getLimitPrice()));

        log.info("Limit order filled: id={} user={} out={} @ {}",
                order.getId(), order.getUserId(), order.getAmountOut(), order.getLimitPrice());
        return true;
    }
}
