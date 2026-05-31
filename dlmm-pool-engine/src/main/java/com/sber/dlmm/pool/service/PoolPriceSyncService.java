package com.sber.dlmm.pool.service;

import com.sber.dlmm.common.enums.PoolStatus;
import com.sber.dlmm.pool.entity.LiquidityPool;
import com.sber.dlmm.pool.repository.LiquidityPoolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sprint 16 — syncs each pool's on-chain price to the real market.
 *
 * <p>The price-oracle now carries real spot prices (CoinGecko crypto, Bank of
 * Russia FX + precious metals; Russian equities are still synthetic — MOEX ISS
 * is geo-blocked from this environment). Without this sync a pool quotes its
 * SEEDED basePrice forever — e.g. SBTC/SRUB stays at 5.0M while the market is
 * 5.24M, so the dashboard ticker and the pool's swap quote disagree.
 *
 * <p>Every minute we read the oracle feed ({@code price_feeds}) and the token
 * catalogue ({@code tokens}) directly from the shared {@code dlmm} database — a
 * scheduled job has no inbound JWT to forward to a price-oracle API call, and
 * both tables live in the same DB, so a couple of read-only SELECTs are simpler
 * and more robust than cross-service HTTP. For each ACTIVE pool we compute the
 * target spot and, if it moved more than {@code MIN_REL_CHANGE}, reprice the
 * pool's whole bin ladder via {@link PoolRepricer} (reserves stay, prices move,
 * F-12 invariant preserved). In a split-DB production deployment these two
 * SELECTs become a price-oracle API call; the logic is identical.
 */
@Service
public class PoolPriceSyncService {

    private static final Logger log = LoggerFactory.getLogger(PoolPriceSyncService.class);
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final String BASE_SYMBOL = "SRUB";
    /** Skip a reprice below this relative move (0.2%) so bins don't churn on noise. */
    private static final BigDecimal MIN_REL_CHANGE = new BigDecimal("0.002");

    private final LiquidityPoolRepository poolRepository;
    private final PoolRepricer poolRepricer;
    private final JdbcTemplate jdbcTemplate;
    private final boolean enabled;

    public PoolPriceSyncService(LiquidityPoolRepository poolRepository,
                                PoolRepricer poolRepricer,
                                JdbcTemplate jdbcTemplate,
                                @Value("${dlmm.pool.price-sync-enabled:true}") boolean enabled) {
        this.poolRepository = poolRepository;
        this.poolRepricer = poolRepricer;
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
    }

    @Scheduled(fixedRateString = "${dlmm.pool.price-sync-rate-ms:60000}", initialDelay = 25_000)
    public void syncPoolPrices() {
        if (!enabled) return;

        Map<String, BigDecimal> prices = loadOraclePrices();
        if (prices.isEmpty()) {
            log.warn("Pool price sync: oracle feed empty, skipping this cycle");
            return;
        }
        Map<UUID, String> symbols = loadTokenSymbols();

        int repriced = 0;
        for (LiquidityPool pool : poolRepository.findByStatus(PoolStatus.ACTIVE)) {
            try {
                String x = symbols.get(pool.getTokenXId());
                String y = symbols.get(pool.getTokenYId());
                BigDecimal target = targetPrice(x, y, prices);
                if (target == null || target.signum() <= 0) continue;

                BigDecimal old = pool.getBasePrice();
                if (old == null || old.signum() <= 0) continue;
                BigDecimal rel = target.subtract(old).abs().divide(old, MC);
                if (rel.compareTo(MIN_REL_CHANGE) < 0) continue; // no meaningful move

                if (poolRepricer.reprice(pool.getId(), target)) repriced++;
            } catch (OptimisticLockingFailureException e) {
                log.debug("Pool {} reprice lost to a concurrent swap; retry next cycle", pool.getId());
            } catch (Exception e) {
                log.warn("Pool price sync failed for pool {}: {}", pool.getId(), e.toString());
            }
        }
        if (repriced > 0) {
            log.info("Pool prices synced to market: {} pool(s) repriced", repriced);
        }
    }

    /**
     * Target spot price (token_y per 1 token_x) for a pool, from oracle RUB
     * prices. Pools quote against SRUB; for a non-SRUB cross we use the RUB
     * ratio. Package-private + static for unit testing.
     */
    static BigDecimal targetPrice(String xSym, String ySym, Map<String, BigDecimal> pricesRub) {
        if (xSym == null || ySym == null) return null;
        if (BASE_SYMBOL.equals(ySym)) {
            return pricesRub.get(xSym); // RUB per X == token_y(SRUB) per X
        }
        if (BASE_SYMBOL.equals(xSym)) {
            BigDecimal py = pricesRub.get(ySym);
            return (py != null && py.signum() > 0) ? BigDecimal.ONE.divide(py, MC) : null;
        }
        BigDecimal px = pricesRub.get(xSym);
        BigDecimal py = pricesRub.get(ySym);
        return (px != null && py != null && py.signum() > 0) ? px.divide(py, MC) : null;
    }

    private Map<String, BigDecimal> loadOraclePrices() {
        Map<String, BigDecimal> m = new HashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT asset_symbol, current_price FROM price_feeds WHERE current_price > 0")) {
            Object price = row.get("current_price"); // NUMERIC → BigDecimal
            if (price instanceof BigDecimal bd) {
                m.put((String) row.get("asset_symbol"), bd);
            }
        }
        return m;
    }

    private Map<UUID, String> loadTokenSymbols() {
        Map<UUID, String> m = new HashMap<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList("SELECT id, symbol FROM tokens")) {
            Object id = row.get("id"); // UUID (pg driver) or String
            if (id != null) {
                m.put(UUID.fromString(id.toString()), (String) row.get("symbol"));
            }
        }
        return m;
    }
}
