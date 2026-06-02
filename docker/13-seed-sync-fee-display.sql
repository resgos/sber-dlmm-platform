-- ============================================================================
-- 13-seed-sync-fee-display.sql
--
-- Makes each LP position's DISPLAYED unclaimed fees equal what a claim actually
-- pays. The Positions page (pool-engine getUserPositions) shows:
--
--     lp_positions.unclaimed_fee_x/y      (stored column)
--   + LIVE delta = Σ over the position's bins of
--                  feeFromGrowth(pool_bins.fee_growth_* − lp_positions.last_fee_growth_*)
--
-- while a claim settles fee_accruals (fee-service). Three independent numbers,
-- seeded/accrued separately, so the page OVERSTATED the claimable amount:
--   • the stored column was seeded with different magnitudes than the accruals;
--   • the live delta added pool-engine fee growth on top — and it was NOT reset
--     on claim, so after claiming the button stayed enabled and a re-claim paid
--     0 ("кнопка активна после забора / даёт забрать ещё раз").
--
-- Fix — bring both display parts to the claim ledger:
--   1. stored column  := Σ of the position's UNCLAIMED accruals, per token
--                        (X = pool.token_x_id, Y = pool.token_y_id);
--   2. snapshot       := MAX(fee_growth) over the position's bins, which zeroes
--                        the live delta (getUserPositions clamps a non-positive
--                        delta to 0). MAX is the minimal single value that zeroes
--                        the multi-bin delta under the simplistic single-snapshot
--                        model (see task #34).
--
-- FeeService.claimFees now applies the same reset+advance on every claim (auto or
-- manual), so display, live delta and ledger stop drifting going forward.
--
-- Idempotent: re-running recomputes the same sums/maxima — safe on an existing DB.
-- On a fresh init it runs after the accrual seeds, so the seeded state shows
-- exactly the claimable amount from the first boot.
--
-- One-time apply to an ALREADY-seeded environment (init scripts only run on an
-- empty data dir):
--   docker exec -i dlmm-postgres psql -U "$DB_USER" -d dlmm < docker/13-seed-sync-fee-display.sql
-- ============================================================================

UPDATE lp_positions AS p SET
  unclaimed_fee_x = COALESCE((
      SELECT SUM(fa.amount) FROM fee_accruals fa
      WHERE fa.position_id = p.id
        AND fa.token_id = (SELECT token_x_id FROM liquidity_pools WHERE id = p.pool_id)
        AND fa.claimed = false), 0),
  unclaimed_fee_y = COALESCE((
      SELECT SUM(fa.amount) FROM fee_accruals fa
      WHERE fa.position_id = p.id
        AND fa.token_id = (SELECT token_y_id FROM liquidity_pools WHERE id = p.pool_id)
        AND fa.claimed = false), 0),
  last_fee_growth_x = COALESCE((
      SELECT MAX(b.fee_growth_x) FROM position_bins pb
      JOIN pool_bins b ON b.pool_id = p.pool_id AND b.bin_id = pb.bin_id
      WHERE pb.position_id = p.id), p.last_fee_growth_x),
  last_fee_growth_y = COALESCE((
      SELECT MAX(b.fee_growth_y) FROM position_bins pb
      JOIN pool_bins b ON b.pool_id = p.pool_id AND b.bin_id = pb.bin_id
      WHERE pb.position_id = p.id), p.last_fee_growth_y);
