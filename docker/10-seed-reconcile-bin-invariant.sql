-- 10-seed-reconcile-bin-invariant.sql — F-12 fix (documented open bug).
--
-- Symptom (CLAUDE.md / SESSION-HANDOFF §5): some seeded bins violate the DLMM
-- invariant  reserve_x·price + reserve_y = liquidity  — the 210 history swaps
-- (03) and the 02/04 bin generators set reserves/liquidity inconsistently (e.g.
-- above-mid bins took liquidity = total_tvl_y/11 instead of reserve_x·price).
-- Consequence: an isolated add→remove (no swaps between) over-returns the quote
-- token (~49%, SBTC worst), because a position's claim
--   amountX = reserve_x · shares / liquidity
-- uses a `liquidity` that is too small, so shares/liquidity exceeds the
-- position's real fraction of the bin.
--
-- This reconciliation, run LAST (after 02–09), restores correctness for EVERY
-- pool without editing the seed inserts:
--   1) liquidity := round(reserve_x·price + reserve_y)   — the invariant.
--   2) where seeded positions collectively OWN more of a bin than it holds
--      (Σ shares > liquidity), scale those shares down proportionally so the
--      sum == liquidity. After this a position can never claim more than its
--      true fraction of the bin's reserves → add→remove is fair, and the
--      "Ваша доля" %/value can't exceed 100%.
--   3) total_liquidity_shares := Σ of the position's (possibly scaled) bin shares.
-- Then it self-checks both post-conditions and logs the result.
--
-- Idempotent via the shared `seed_markers` row (06/08 use the same table); a
-- fresh `docker compose down -v` wipes the marker so a clean re-seed re-applies.
-- Apply in PRE-DEMO STEP 0b after 09-seed-ohlcv-backfill.sql.

CREATE TABLE IF NOT EXISTS seed_markers (
    name       TEXT PRIMARY KEY,
    applied_at TIMESTAMP NOT NULL DEFAULT NOW()
);

DO $$
DECLARE
    off_invariant BIGINT;
    over_owned    BIGINT;
BEGIN
    IF EXISTS (SELECT 1 FROM seed_markers WHERE name = '10-reconcile-bin-invariant') THEN
        RAISE NOTICE '10-reconcile-bin-invariant already applied — skipping';
        RETURN;
    END IF;

    -- 1) Restore the bin invariant: liquidity = value the bin actually holds.
    UPDATE pool_bins
    SET liquidity = GREATEST(1, ROUND(reserve_x::numeric * price + reserve_y))::bigint;

    -- 2) Cap per-bin ownership: where positions collectively own more than the
    --    bin holds, scale them down proportionally to sum == liquidity.
    WITH bin_owned AS (
        SELECT p.pool_id, pb.bin_id, SUM(pb.liquidity_shares) AS owned
        FROM   position_bins pb
        JOIN   lp_positions  p ON p.id = pb.position_id
        GROUP  BY p.pool_id, pb.bin_id
    )
    UPDATE position_bins pb
    SET liquidity_shares = GREATEST(1, FLOOR(
            pb.liquidity_shares::numeric * b.liquidity / NULLIF(bo.owned, 0)
        ))::bigint
    FROM lp_positions p, pool_bins b, bin_owned bo
    WHERE pb.position_id = p.id
      AND b.pool_id  = p.pool_id AND b.bin_id  = pb.bin_id
      AND bo.pool_id = p.pool_id AND bo.bin_id = pb.bin_id
      AND bo.owned > b.liquidity;     -- only the over-owned bins

    -- 3) Recompute each position's total shares from its (scaled) bin shares.
    UPDATE lp_positions p
    SET total_liquidity_shares = COALESCE(
            (SELECT SUM(pb.liquidity_shares) FROM position_bins pb WHERE pb.position_id = p.id),
            p.total_liquidity_shares);

    -- ── self-check: both post-conditions must be 0 ─────────────────────────
    SELECT COUNT(*) INTO off_invariant
    FROM   pool_bins
    WHERE  ABS((reserve_x::numeric * price + reserve_y) - liquidity) > 1;   -- >1 unit = real drift, not rounding

    SELECT COUNT(*) INTO over_owned
    FROM (
        SELECT 1
        FROM   position_bins pb
        JOIN   lp_positions  p ON p.id = pb.position_id
        JOIN   pool_bins     b ON b.pool_id = p.pool_id AND b.bin_id = pb.bin_id
        GROUP  BY p.pool_id, pb.bin_id, b.liquidity
        HAVING SUM(pb.liquidity_shares) > b.liquidity
    ) z;

    IF off_invariant > 0 OR over_owned > 0 THEN
        RAISE WARNING 'F-12 reconcile INCOMPLETE: % bins off-invariant, % bins over-owned', off_invariant, over_owned;
    ELSE
        RAISE NOTICE 'F-12 reconcile OK — all bins satisfy reserve_x*price+reserve_y=liquidity and no bin is over-owned';
    END IF;

    INSERT INTO seed_markers(name) VALUES ('10-reconcile-bin-invariant');
END $$;
