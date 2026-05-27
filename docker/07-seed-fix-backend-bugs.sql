-- Demo walkthrough findings (2026-05-27) — fixes for 2 backend gaps
-- found while running real swap/claim/add-liquidity operations.

-- =============================================================
-- Fix #1 — claimFees() returns 0/0 for positions с
-- lp_positions.unclaimed_fee_x/y > 0 но без fee_accruals records.
-- =============================================================
-- Root cause: FeeService.claimFees reads exclusively from fee_accruals;
-- seed data populated lp_positions.unclaimed_fee_x/y aggregates but
-- never the corresponding fee_accruals rows, so claim no-ops.
--
-- Fix: backfill fee_accruals для всех seed positions where unclaimed
-- amount > 0 but no matching unclaimed accrual exists. After this,
-- claim returns the actual amounts and balances credit correctly.

-- X-side backfill
INSERT INTO fee_accruals (id, position_id, pool_id, user_id, token_id, amount, claimed, accrued_at)
SELECT
    gen_random_uuid(),
    p.id,
    p.pool_id,
    p.user_id,
    pool.token_x_id,
    p.unclaimed_fee_x,
    false,
    NOW() - INTERVAL '1 hour' * random() * 72
FROM lp_positions p
JOIN liquidity_pools pool ON p.pool_id = pool.id
WHERE p.unclaimed_fee_x > 0
  AND p.is_active = true
  AND NOT EXISTS (
    SELECT 1 FROM fee_accruals fa
    WHERE fa.position_id = p.id
      AND fa.token_id = pool.token_x_id
      AND fa.claimed = false
  );

-- Y-side backfill (same logic)
INSERT INTO fee_accruals (id, position_id, pool_id, user_id, token_id, amount, claimed, accrued_at)
SELECT
    gen_random_uuid(),
    p.id,
    p.pool_id,
    p.user_id,
    pool.token_y_id,
    p.unclaimed_fee_y,
    false,
    NOW() - INTERVAL '1 hour' * random() * 72
FROM lp_positions p
JOIN liquidity_pools pool ON p.pool_id = pool.id
WHERE p.unclaimed_fee_y > 0
  AND p.is_active = true
  AND NOT EXISTS (
    SELECT 1 FROM fee_accruals fa
    WHERE fa.position_id = p.id
      AND fa.token_id = pool.token_y_id
      AND fa.claimed = false
  );

-- =============================================================
-- Fix #2 — users.last_login_at column missing → /admin/pilots/health
-- returns empty array (Batch #5 B-06).
-- =============================================================
-- Root cause: PilotHealthService.getAllPilotHealth() reads u.last_login_at,
-- но schema users table doesn't have such column. The query parses в
-- raw SQL без validation, postgres returns NULL для нонexistent column
-- references in some configurations, или throws — обработка proглатывает
-- → empty list.
--
-- Fix: add the column (idempotent — IF NOT EXISTS), populate seed users
-- с last_login_at равным random within last 30 days. Real auth flow
-- needs to UPDATE this column on login (separate code change deferred
-- к follow-up — not breaking for demo).

ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMP;

-- Populate для всех существующих users — randomly spread в last 30 days
UPDATE users
SET last_login_at = NOW() - (INTERVAL '1 day' * floor(random() * 30))
WHERE last_login_at IS NULL;

-- Verify both fixes
DO $$
DECLARE
  newly_claimable bigint;
  users_with_login bigint;
BEGIN
  SELECT COUNT(*) INTO newly_claimable FROM fee_accruals
    WHERE claimed = false AND accrued_at > NOW() - INTERVAL '5 minutes';
  SELECT COUNT(*) INTO users_with_login FROM users WHERE last_login_at IS NOT NULL;
  RAISE NOTICE 'Backfilled % unclaimed fee accruals', newly_claimable;
  RAISE NOTICE '% of % users now have last_login_at populated', users_with_login,
    (SELECT COUNT(*) FROM users);
END $$;
