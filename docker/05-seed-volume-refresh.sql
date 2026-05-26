-- F-01 (UX-FINDINGS 2026-05-26) — refresh seed-data timestamps so
-- volume_24h aggregation looks alive за demo. Without this, pools
-- listing shows 0₽/0% for всех ради того, что seed transactions
-- are static и aging out of the 24h rolling window.
--
-- Runs on container start (postgres entrypoint, after init-db.sql,
-- 02-extended-assets.sql, 03-seed-trading-history.sql, 04-spasibo-seed.sql).
--
-- Strategy:
--   1. Bump all SWAP transactions from the last 7 days into the last 24h
--      window — actual content unchanged, just timestamp нудан.
--   2. Force-recalc volume_24h в liquidity_pools (the @Scheduled job
--      will keep it fresh after the first run, ~4min cadence).
--   3. Ensure no pool shows volume_24h=0 — fall back to total_tvl_y/30
--      (roughly 3.3% daily-of-TVL turnover, gives ~1-5% APY at typical
--      fees — realistic for treasury market-making).

-- Step 1: bump timestamps
UPDATE transactions
SET created_at = NOW() - (random() * INTERVAL '23 hours')
WHERE tx_type = 'SWAP'
  AND created_at > NOW() - INTERVAL '7 days';

-- Step 2: force-recalc volume_24h from updated data
UPDATE liquidity_pools p
SET volume_24h = COALESCE(
    (SELECT SUM(amount_in)
     FROM transactions t
     WHERE t.pool_id = p.id
       AND t.tx_type = 'SWAP'
       AND t.status = 'CONFIRMED'
       AND t.created_at >= NOW() - INTERVAL '24 hours'),
    0
);

-- Step 3: floor to realistic-for-demo level
UPDATE liquidity_pools
SET volume_24h = GREATEST(volume_24h, (total_tvl_y / 30)::bigint)
WHERE total_tvl_y > 0;
