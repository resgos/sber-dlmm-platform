-- ============================================================================
-- 13-seed-sync-fee-display.sql
--
-- Aligns each LP position's DISPLAYED unclaimed fees
-- (lp_positions.unclaimed_fee_x / unclaimed_fee_y — shown on the Positions page,
-- owned by pool-engine) with the CLAIM LEDGER (fee_accruals — owned by
-- fee-service). The two were seeded independently, so the display OVERSTATED
-- what a claim actually pays: a user saw e.g. 720 SBER "unclaimed", clicked
-- claim, and received 17 — or, after the auto-claim scheduler had already
-- settled the accruals, received 0 while the page still showed a balance
-- ("забор комиссии не получился").
--
-- Fix: set the display = the sum of the position's UNCLAIMED accruals, per token
--   X  ->  fee_accruals for pool.token_x_id
--   Y  ->  fee_accruals for pool.token_y_id
-- After this the Positions page shows EXACTLY what a claim will pay. FeeService
-- now also zeroes the display on every claim (auto or manual, see claimFees), so
-- the two stop drifting going forward.
--
-- Idempotent: re-running recomputes the same sums — safe on an existing DB.
-- On a fresh init it runs after the accrual seeds, so the seeded state is
-- self-consistent from the first boot.
--
-- One-time apply to an ALREADY-seeded environment (the init scripts only run on
-- an empty data dir):
--   docker exec -i dlmm-postgres psql -U "$DB_USER" -d dlmm < docker/13-seed-sync-fee-display.sql
-- ============================================================================

UPDATE lp_positions p SET
  unclaimed_fee_x = COALESCE((
      SELECT SUM(fa.amount) FROM fee_accruals fa
      WHERE fa.position_id = p.id
        AND fa.token_id = (SELECT token_x_id FROM liquidity_pools WHERE id = p.pool_id)
        AND fa.claimed = false), 0),
  unclaimed_fee_y = COALESCE((
      SELECT SUM(fa.amount) FROM fee_accruals fa
      WHERE fa.position_id = p.id
        AND fa.token_id = (SELECT token_y_id FROM liquidity_pools WHERE id = p.pool_id)
        AND fa.claimed = false), 0);
