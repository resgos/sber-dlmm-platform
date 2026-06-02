-- ============================================================================
-- 14-seed-bin-fee-growth.sql
--
-- Gives the seeded LP positions some CLAIMABLE fees under the Meteora per-bin
-- model, so a fresh demo boot shows "fees to claim" and the claim/auto-claim
-- flow has something to settle.
--
-- Model recap (single source of truth = pool-engine per-bin fee growth):
--   owed(bin) = feeFromGrowth(pool_bins.fee_growth - position_bins.fee_growth_checkpoint,
--                             liquidity_shares)   [floor, 1e9 fixed-point]
-- So to seed owed > 0 we set a modest pool_bins.fee_growth on the bins the
-- seeded positions occupy and leave their per-bin checkpoints at 0.
--
-- Also retires the OLD display-side seed that the per-bin model replaces:
--   * lp_positions.unclaimed_fee_x/y is zeroed — per-bin owed is now the source,
--     and a non-zero stored column would double-count on top of it;
--   * seeded fee_accruals rows are marked claimed=true — fee_accruals is now a
--     write-on-claim HISTORY ledger, not the unclaimed source.
--
-- Idempotent: re-running sets the same fee_growth + checkpoints and re-marks
-- history. Supersedes 13-seed-sync-fee-display.sql (now a no-op).
--
-- One-time apply to an already-seeded environment:
--   docker exec -i dlmm-postgres psql -U "$DB_USER" -d dlmm < docker/14-seed-bin-fee-growth.sql
-- ============================================================================

-- 1. Seed per-bin fee growth (accumulated trading fees) on every bin that a
--    position occupies — the active liquidity region. Per-unit-of-liquidity,
--    1e9 fixed-point. ~300000 yields a few-thousand-token owed for the seeded
--    position sizes (matches the previous demo feel). Forced (=) so re-runs and
--    fresh boots are deterministic.
UPDATE pool_bins b SET
    fee_growth_x = 300000,
    fee_growth_y = 300000
WHERE EXISTS (
    SELECT 1 FROM position_bins pb
    JOIN lp_positions p ON p.id = pb.position_id
    WHERE pb.bin_id = b.bin_id AND p.pool_id = b.pool_id
);

-- 2. Reset every position's per-bin checkpoint to 0 so the seeded growth above
--    shows as owed. (Fresh boot: already 0. Existing DB: clears any advanced
--    checkpoints so the seed state is deterministic.)
UPDATE position_bins SET
    fee_growth_checkpoint_x = 0,
    fee_growth_checkpoint_y = 0;

-- 3. Per-bin owed is the single source of truth → zero the legacy display column
--    (otherwise it double-counts on top of the per-bin owed).
UPDATE lp_positions SET
    unclaimed_fee_x = 0,
    unclaimed_fee_y = 0;

-- 4. fee_accruals is now a write-on-claim history ledger → mark the seeded
--    (previously "unclaimed") rows as claimed history so they read as earned/
--    claimed and do not masquerade as an unclaimed source.
UPDATE fee_accruals SET
    claimed = true,
    claimed_at = COALESCE(claimed_at, accrued_at)
WHERE claimed = false;

-- 5. Self-check: report total owed_y across active positions; warn if zero.
DO $$
DECLARE owed_y bigint;
BEGIN
    SELECT COALESCE(SUM(GREATEST(b.fee_growth_y - pb.fee_growth_checkpoint_y, 0)
                        * pb.liquidity_shares / 1000000000), 0)
    INTO owed_y
    FROM position_bins pb
    JOIN lp_positions p ON p.id = pb.position_id
    JOIN pool_bins b ON b.pool_id = p.pool_id AND b.bin_id = pb.bin_id
    WHERE p.is_active;
    RAISE NOTICE '14-seed: total owed_y across active positions = %', owed_y;
    IF owed_y <= 0 THEN
        RAISE WARNING '14-seed: no owed fees seeded — positions will show 0 claimable';
    END IF;
END $$;
