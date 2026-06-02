-- ============================================================================
-- 15-seed-fix-active-bin.sql
--
-- Fixes pools whose active_bin_id is STRANDED BELOW their quote-token (Y / bid)
-- liquidity, which silently breaks sell swaps.
--
-- Background: in this LB-DLMM, an X->Y swap (selling the base token) walks DOWN
-- from the active bin consuming each bin's reserve_y. So all the Y must sit at or
-- BELOW the active bin. The earlier reserve-reconciliation/rescale seeds (10, 11)
-- redistributed Y across a band of bins around the anchor (8388608) but did NOT
-- update liquidity_pools.active_bin_id, which had been seeded at the bottom of the
-- range (8388599) back when all the Y lived in one bin. Result: the active pointer
-- ended up 9 bins BELOW the Y band, so a sell walked down into empty bins and could
-- only return the active bin's tiny Y — e.g. selling 44.4M SETH returned ~358k SRUB
-- instead of ~16.7M, because the SRUB in bins 8388600..8388608 was above the
-- pointer and unreachable. (The bin-drain logic is correct; the start bin was wrong.)
--
-- Fix: for any pool whose active bin sits BELOW the bin holding the most Y (i.e. the
-- pointer is stranded under the bids), reset active_bin_id to the TOP of the Y range
-- (MAX bin with reserve_y > 0) — the real price level where Y-below meets X-above.
-- Healthy pools (active already at/above their Y peak, e.g. the canonical anchor
-- 8388608 with Y below + X above, and the dust-Y-above smear in SUSDT/SRUB) are NOT
-- touched. Swaps maintain the pointer correctly afterwards, so this is one-time.
--
-- Idempotent: re-running is a no-op once pointers are consistent. Runs after 10/11.
-- ============================================================================

UPDATE liquidity_pools lp SET active_bin_id = (
        SELECT MAX(bin_id) FROM pool_bins b WHERE b.pool_id = lp.id AND b.reserve_y > 0
    )
WHERE EXISTS (SELECT 1 FROM pool_bins b WHERE b.pool_id = lp.id AND b.reserve_y > 0)
  AND lp.active_bin_id < (
        -- the bin holding the MOST Y (the bid peak); if the active pointer is below
        -- it, sells can't reach the bulk of the Y.
        SELECT bin_id FROM pool_bins b WHERE b.pool_id = lp.id AND b.reserve_y > 0
        ORDER BY b.reserve_y DESC, b.bin_id DESC LIMIT 1
    );

-- Self-check: report any pool still stranded (active below its Y peak) — expect none.
DO $$
DECLARE bad int;
BEGIN
    SELECT count(*) INTO bad FROM liquidity_pools lp
    WHERE EXISTS (SELECT 1 FROM pool_bins b WHERE b.pool_id = lp.id AND b.reserve_y > 0)
      AND lp.active_bin_id < (SELECT bin_id FROM pool_bins b WHERE b.pool_id = lp.id AND b.reserve_y > 0
                              ORDER BY b.reserve_y DESC, b.bin_id DESC LIMIT 1);
    RAISE NOTICE '15-seed: pools still stranded below their Y peak = % (expect 0)', bad;
END $$;
