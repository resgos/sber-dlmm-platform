-- ============================================================================
-- 18-seed-fix-bin-composition.sql
--
-- Restores the DLMM bin-composition rule on seeded pools: bins BELOW the
-- active bin hold ONLY token Y (quote), bins ABOVE hold ONLY token X (base);
-- only the active bin is mixed. The seeded trading history violated this
-- (X parked below the active bin), which made deposit composition flip
-- instantly: an LP deposited 0.53 SETH + 54.5k SRUB and the position showed
-- ~1.0 SETH + 198 SRUB, and a 100% remove returned the flipped mix (found
-- live 2026-06-12 by an add->remove smoke on SETH/SRUB). Value was conserved
-- (the F-12 liquidity invariant held) — only the X/Y split was wrong, because
-- a position's slice is pro-rata of the bin's CURRENT reserves.
--
-- Fix is VALUE-PRESERVING by construction: misplaced X below the active bin is
-- converted into Y at that bin's own price (and Y above into X), so
-- reserveX*price + reserveY — and therefore bin liquidity and every position's
-- share value — stays exactly the same (mod <=1 unit rounding). liquidity and
-- position_bins are NOT touched. Pool TVL aggregates are recomputed from bins.
--
-- Idempotent: after the sweep the WHERE clauses match nothing. Re-runnable any
-- time (e.g. after reseeding history):
--   FORCE_REAPPLY=18-seed-fix-bin-composition bash scripts/seed-demo.sh
-- ============================================================================

BEGIN;

-- Below the active bin: X -> Y at the bin's own price; bin keeps only Y.
UPDATE pool_bins b
SET reserve_y = b.reserve_y + round(
        b.reserve_x::numeric
        * (lp.base_price::numeric * power(1.0 + lp.bin_step / 10000.0, b.bin_id - 8388608))::numeric),
    reserve_x = 0
FROM liquidity_pools lp
WHERE lp.id = b.pool_id
  AND lp.status = 'ACTIVE'
  AND b.bin_id < lp.active_bin_id
  AND b.reserve_x > 0;

-- Above the active bin: Y -> X at the bin's own price; bin keeps only X.
UPDATE pool_bins b
SET reserve_x = b.reserve_x + round(
        b.reserve_y::numeric
        / NULLIF((lp.base_price::numeric * power(1.0 + lp.bin_step / 10000.0, b.bin_id - 8388608))::numeric, 0)),
    reserve_y = 0
FROM liquidity_pools lp
WHERE lp.id = b.pool_id
  AND lp.status = 'ACTIVE'
  AND b.bin_id > lp.active_bin_id
  AND b.reserve_y > 0;

-- TVL aggregates follow the new composition (sum over bins).
UPDATE liquidity_pools lp
SET total_tvl_x = COALESCE((SELECT SUM(b.reserve_x) FROM pool_bins b WHERE b.pool_id = lp.id), 0),
    total_tvl_y = COALESCE((SELECT SUM(b.reserve_y) FROM pool_bins b WHERE b.pool_id = lp.id), 0)
WHERE lp.status = 'ACTIVE';

COMMIT;

-- Self-check 1: composition rule holds everywhere.
-- Self-check 2: the liquidity invariant survived the sweep (value preserved).
DO $$
DECLARE below_x int; above_y int; drift int;
BEGIN
    SELECT count(*) INTO below_x FROM pool_bins b JOIN liquidity_pools lp ON lp.id=b.pool_id
    WHERE lp.status='ACTIVE' AND b.bin_id < lp.active_bin_id AND b.reserve_x > 0;
    SELECT count(*) INTO above_y FROM pool_bins b JOIN liquidity_pools lp ON lp.id=b.pool_id
    WHERE lp.status='ACTIVE' AND b.bin_id > lp.active_bin_id AND b.reserve_y > 0;
    -- bins whose value drifted from liquidity by more than 0.1% (would mean a bug here)
    SELECT count(*) INTO drift FROM pool_bins b JOIN liquidity_pools lp ON lp.id=b.pool_id
    WHERE lp.status='ACTIVE' AND b.liquidity > 0 AND abs(
        (b.reserve_x::numeric * (lp.base_price::numeric * power(1.0 + lp.bin_step/10000.0, b.bin_id-8388608)) + b.reserve_y::numeric)
        - b.liquidity::numeric) > b.liquidity::numeric * 0.001;
    RAISE NOTICE '18-seed: X-below-active bins = % (expect 0); Y-above-active bins = % (expect 0); >0.1%% value-vs-liquidity drift bins = % (informational)', below_x, above_y, drift;
END $$;
