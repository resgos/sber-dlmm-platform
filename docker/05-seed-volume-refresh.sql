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

-- Step 1 + 1b run in ONE transaction (review): they are separate statements, and in
-- autocommit a concurrent API reader between them would see created_at already bumped
-- but confirmed_at still historical — a visible invariant break on a live demo stack.
BEGIN;

-- Step 1: bump timestamps. The 5-second floor keeps the +2s settlement stamp from
-- Step 1b strictly in the past even when random() lands at ~0 (review).
UPDATE transactions
SET created_at = NOW() - (random() * INTERVAL '23 hours') - INTERVAL '5 seconds'
WHERE tx_type = 'SWAP'
  AND created_at > NOW() - INTERVAL '7 days';

-- Step 1b (arch-audit P0-2): the bump above moves created_at into the last 24h but
-- leaves confirmed_at at the original historical settlement time, so confirmed_at <
-- created_at ("confirmed before created") — the temporal invariant
-- created_at <= updated_at <= confirmed_at breaks every time volume is refreshed,
-- including the documented `FORCE_REAPPLY=05-seed-volume-refresh` 24h refresh, which
-- does NOT re-run 16-seed. A swap settles in ~seconds, so pin updated/confirmed just
-- after the new created_at. NOT window-limited, so it also sweeps up any straggler a
-- prior bump left outside the 7-day window. Idempotent: once consistent the WHERE no
-- longer matches. (16-seed still does the full-ledger pass during a cold seed.)
UPDATE transactions
SET confirmed_at = created_at + INTERVAL '2 seconds',
    updated_at   = created_at + INTERVAL '1 second'
WHERE tx_type = 'SWAP' AND confirmed_at IS NOT NULL
  AND (created_at > confirmed_at OR updated_at > confirmed_at OR created_at > updated_at);

-- Step 1c (review): Step 1 bumps created_at for ALL window SWAPs, including ones with
-- no settlement yet (confirmed_at IS NULL — e.g. PENDING/FAILED), which Step 1b's
-- confirmed-only WHERE skips; without this their updated_at would lag behind the new
-- created_at. Mirrors 16-seed's pending-row rule.
UPDATE transactions
SET updated_at = created_at
WHERE tx_type = 'SWAP' AND confirmed_at IS NULL
  AND updated_at < created_at;

COMMIT;

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
