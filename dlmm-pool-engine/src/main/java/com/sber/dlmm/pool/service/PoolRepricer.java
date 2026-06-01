package com.sber.dlmm.pool.service;

import com.sber.dlmm.common.enums.PoolStatus;
import com.sber.dlmm.common.util.BinMath;
import com.sber.dlmm.pool.entity.LiquidityPool;
import com.sber.dlmm.pool.entity.PoolBin;
import com.sber.dlmm.pool.repository.LiquidityPoolRepository;
import com.sber.dlmm.pool.repository.PoolBinRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.UUID;

/**
 * Reprices ONE pool (its anchor basePrice + its whole bin ladder) to a target
 * spot price, in its OWN transaction — so a concurrent swap's optimistic-lock
 * conflict rolls back only this pool, not the whole {@link PoolPriceSyncService}
 * sweep.
 */
@Service
public class PoolRepricer {

    private static final MathContext MC = MathContext.DECIMAL64;

    private final LiquidityPoolRepository poolRepository;
    private final PoolBinRepository poolBinRepository;

    /**
     * @param poolRepository    pool row (base price + status) read &amp; updated
     * @param poolBinRepository  the pool's bin ladder, rescaled per reprice
     */
    public PoolRepricer(LiquidityPoolRepository poolRepository, PoolBinRepository poolBinRepository) {
        this.poolRepository = poolRepository;
        this.poolBinRepository = poolBinRepository;
    }

    /**
     * Shift the pool's whole price ladder so its anchor (basePrice) becomes
     * {@code target}. Reserves stay put (LPs keep their tokens) — only prices
     * move, exactly as the pool would behave if the market moved and arbitrage
     * had walked it there. Each bin's price is scaled by the same factor (the
     * geometric spacing is preserved), then its liquidity + composition factor
     * are recomputed from the new price so the F-12 bin invariant
     * ({@code liquidity = reserveX·price + reserveY}) keeps holding.
     *
     * <p>NB: {@code activeBinId} is deliberately NOT moved here. The whole ladder is
     * rescaled by the same {@code factor}, so the bin at {@code activeBinId} keeps
     * {@code price == basePrice} automatically; advancing the anchor id too would
     * double-count the move and misalign the add-liquidity X/Y split. (The math-#14
     * Stage-3 createPool half-bin precision tweak is left for a dedicated pass.)
     *
     * @param poolId pool to reprice
     * @param target new anchor (base) price the ladder should be shifted to
     * @return true if repriced; false if the pool vanished / went inactive / had
     *         a non-positive base or target price.
     */
    @Transactional
    public boolean reprice(UUID poolId, BigDecimal target) {
        LiquidityPool pool = poolRepository.findById(poolId).orElse(null);
        if (pool == null || pool.getStatus() != PoolStatus.ACTIVE) return false;
        BigDecimal old = pool.getBasePrice();
        if (old == null || old.signum() <= 0 || target == null || target.signum() <= 0) return false;

        BigDecimal factor = target.divide(old, MC);
        List<PoolBin> bins = poolBinRepository.findByPoolId(poolId);
        for (PoolBin bin : bins) {
            BigDecimal base = (bin.getPrice() != null && bin.getPrice().signum() > 0)
                    ? bin.getPrice()
                    : BinMath.binPriceAtBin(old, pool.getBinStep(), bin.getBinId(), pool.getActiveBinId());
            BigDecimal newPrice = base.multiply(factor, MC);
            long liquidity = BinMath.binLiquidity(bin.getReserveX(), bin.getReserveY(), newPrice);
            bin.setPrice(newPrice);
            bin.setLiquidity(liquidity);
            bin.setCompositionFactor(BinMath.compositionFactor(bin.getReserveY(), liquidity));
        }
        poolBinRepository.saveAll(bins);

        pool.setBasePrice(target);
        poolRepository.save(pool);
        return true;
    }
}
