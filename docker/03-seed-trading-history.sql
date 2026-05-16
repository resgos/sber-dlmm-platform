-- ============================================================================
-- Sber DLMM Platform — Seed: 30 days of trading history + LP positions
-- ============================================================================
-- Demo-readiness seed (Sprint 1 #7). Makes the dashboards non-empty:
--   * ~200 swap transactions distributed across pools, users, and 30 days
--   * 8 extra LP positions in popular pools so /admin/dashboard shows
--     activePositions > 0 and fee dashboards have something to render
--   * Matching position_bins and fee_accruals so per-position UIs look real
--
-- Safe to apply on top of init-db.sql + 02-extended-assets.sql:
--   * All inserts wrapped in ON CONFLICT DO NOTHING
--   * Generated UUIDs use a deterministic prefix ('77', '88', '99')
--     so re-runs collide on PK and skip cleanly.
--   * Pool/user lookups are dynamic — works against any superset of the
--     base seed.
--
-- Apply directly:
--   docker exec -i dlmm-postgres psql -U dlmm -d dlmm < 03-seed-trading-history.sql
-- ============================================================================

-- ── 1. Additional LP positions across high-traffic pools ──────────────────
-- Picks 8 (user, pool, strategy) tuples that don't collide with the
-- existing d0000000-* positions. Range and fees calibrated to look like
-- positions that have been earning for a couple of weeks.
INSERT INTO lp_positions (id, user_id, pool_id, bin_range_min, bin_range_max, strategy, total_liquidity_shares, unclaimed_fee_x, unclaimed_fee_y, is_active, created_at)
SELECT
  ('77000000-0000-0000-0000-00000000000' || row_num::text)::uuid,
  user_id::uuid,
  pool_id::uuid,
  8388603, 8388613,
  strategy,
  liquidity_shares,
  unclaimed_x,
  unclaimed_y,
  true,
  NOW() - (days_ago || ' days')::interval
FROM (VALUES
  (1, 'a0000000-0000-0000-0000-000000000002', 'c0000000-0000-0000-0000-000000000004', 'SPOT',    180000000000,  95000000,   72000000,  18),  -- ivanov, SRUB/SGOLD
  (2, 'a0000000-0000-0000-0000-000000000003', 'c0000000-0000-0000-0000-000000000005', 'CURVE',   220000000000, 140000000,  110000000,  21),  -- sidorov, SRUB/SSILV
  (3, 'a0000000-0000-0000-0000-000000000002', 'c0000000-0000-0000-0000-000000000101', 'SPOT',    310000000000, 210000000,  185000000,  14),  -- ivanov, SRUB/SBER
  (4, 'a0000000-0000-0000-0000-000000000003', 'c0000000-0000-0000-0000-000000000102', 'BID_ASK', 280000000000, 165000000,  140000000,  12),  -- sidorov, SRUB/SGAZ
  (5, 'a0000000-0000-0000-0000-000000000002', 'c0000000-0000-0000-0000-000000000111', 'CURVE',   195000000000, 120000000,  100000000,  10),  -- ivanov, SRUB/SUSDT
  (6, 'a0000000-0000-0000-0000-000000000003', 'c0000000-0000-0000-0000-000000000112', 'SPOT',    240000000000, 180000000,  150000000,   8),  -- sidorov, SRUB/SEUR
  (7, 'a0000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000004', 'CURVE',   400000000000, 280000000,  240000000,  25),  -- admin, SRUB/SGOLD
  (8, 'a0000000-0000-0000-0000-000000000002', 'c0000000-0000-0000-0000-000000000110', 'SPOT',    175000000000,  90000000,   75000000,   6)   -- ivanov, SRUB/SRTSI
) AS t(row_num, user_id, pool_id, strategy, liquidity_shares, unclaimed_x, unclaimed_y, days_ago)
WHERE EXISTS (SELECT 1 FROM liquidity_pools WHERE id = pool_id::uuid)
ON CONFLICT (id) DO NOTHING;

-- ── 2. position_bins for the new positions (11 bins per position) ─────────
INSERT INTO position_bins (position_id, bin_id, liquidity_shares)
SELECT p.id, b.bin_id, p.total_liquidity_shares / 11
FROM lp_positions p
CROSS JOIN LATERAL (
  SELECT generate_series(p.bin_range_min, p.bin_range_max) AS bin_id
) b
WHERE p.id::text LIKE '77000000-%'
ON CONFLICT (position_id, bin_id) DO NOTHING;

-- ── 3. 30 days of swap transactions (~200 across pools and users) ─────────
-- Generates 7 transactions per day for 30 days = 210 transactions.
-- Each picks a random pool (weighted toward SRUB pairs), random direction,
-- random VERIFIED user, and a realistic amount (1% to 5% of pool TVL).
-- Status: 95% CONFIRMED, 3% PENDING (only last 4h), 2% FAILED.
DO $$
DECLARE
  v_day_offset INT;
  v_tx_in_day INT;
  v_pool_record RECORD;
  v_user_id UUID;
  v_users UUID[] := ARRAY[
    'a0000000-0000-0000-0000-000000000001',
    'a0000000-0000-0000-0000-000000000002',
    'a0000000-0000-0000-0000-000000000003'
  ]::uuid[];
  v_direction INT;
  v_token_in UUID;
  v_token_out UUID;
  v_amount_in BIGINT;
  v_amount_out BIGINT;
  v_fee BIGINT;
  v_fee_rate DECIMAL(10,6);
  v_bins INT;
  v_status VARCHAR(30);
  v_status_pick INT;
  v_ts TIMESTAMP;
  v_seq INT := 0;
  v_tx_id UUID;
BEGIN
  FOR v_day_offset IN 0..29 LOOP
    FOR v_tx_in_day IN 1..7 LOOP
      v_seq := v_seq + 1;

      -- Pick a pool at random (weighted: SRUB pairs slightly more likely).
      SELECT id, token_x_id, token_y_id, base_fee_bps
        INTO v_pool_record
        FROM liquidity_pools
       WHERE status = 'ACTIVE'
       ORDER BY (CASE WHEN token_y_id = 'b0000000-0000-0000-0000-000000000001'::uuid
                      THEN random() * 1.5 ELSE random() END) DESC
       LIMIT 1 OFFSET (v_seq % 11);

      CONTINUE WHEN v_pool_record.id IS NULL;

      -- Pick a verified user
      v_user_id := v_users[1 + (v_seq % 3)];

      -- Direction: 60% buy token X with Y, 40% sell
      v_direction := CASE WHEN random() < 0.6 THEN 1 ELSE 0 END;
      IF v_direction = 1 THEN
        v_token_in  := v_pool_record.token_y_id;
        v_token_out := v_pool_record.token_x_id;
        v_amount_in := (50000000 + (random() * 950000000)::bigint);  -- 0.5 → 10 SRUB units
      ELSE
        v_token_in  := v_pool_record.token_x_id;
        v_token_out := v_pool_record.token_y_id;
        v_amount_in := (10000000 + (random() * 190000000)::bigint);  -- 0.1 → 2 X units
      END IF;

      v_fee_rate := v_pool_record.base_fee_bps::decimal / 10000.0;
      v_fee := (v_amount_in * v_fee_rate)::bigint;
      v_bins := 1 + (random() * 3)::int;
      v_amount_out := ((v_amount_in - v_fee) * (0.95 + random() * 0.10))::bigint;

      -- Timestamp: random second within the day v_day_offset days ago
      v_ts := date_trunc('day', NOW() - (v_day_offset || ' days')::interval)
              + (random() * 86400 || ' seconds')::interval;

      -- Status mix
      v_status_pick := (random() * 100)::int;
      IF v_day_offset = 0 AND v_ts > NOW() - interval '4 hours' AND v_status_pick < 3 THEN
        v_status := 'PENDING';
      ELSIF v_status_pick < 2 THEN
        v_status := 'FAILED';
        v_amount_out := NULL;
        v_fee := 0;
      ELSE
        v_status := 'CONFIRMED';
      END IF;

      v_tx_id := ('88000000-0000-0000-0000-' || LPAD(v_seq::text, 12, '0'))::uuid;

      INSERT INTO transactions (
        id, tx_type, status, user_id, pool_id, token_in_id, amount_in,
        token_out_id, amount_out, fee_amount, fee_rate, bins_crossed,
        idempotency_key, created_at, updated_at, confirmed_at
      ) VALUES (
        v_tx_id, 'SWAP', v_status, v_user_id, v_pool_record.id,
        v_token_in, v_amount_in,
        v_token_out, v_amount_out,
        v_fee, v_fee_rate, v_bins,
        'seed-history-' || v_seq,
        v_ts, v_ts,
        CASE WHEN v_status = 'CONFIRMED' THEN v_ts ELSE NULL END
      ) ON CONFLICT (idempotency_key) DO NOTHING;

      -- Fee accrual records — split fee between the two existing positions
      -- in this pool (if any) proportional to their liquidity shares.
      -- Only for CONFIRMED swaps.
      IF v_status = 'CONFIRMED' AND v_fee > 0 THEN
        INSERT INTO fee_accruals (id, position_id, pool_id, user_id, token_id, amount, tx_id, claimed, accrued_at)
        SELECT
          gen_random_uuid(),
          pos.id,
          v_pool_record.id,
          pos.user_id,
          v_token_in,
          (v_fee * pos.total_liquidity_shares
            / NULLIF(SUM(pos.total_liquidity_shares) OVER (PARTITION BY pos.pool_id), 0))::bigint,
          v_tx_id,
          false,
          v_ts
        FROM lp_positions pos
        WHERE pos.pool_id = v_pool_record.id
          AND pos.is_active = true
        LIMIT 3;
      END IF;
    END LOOP;
  END LOOP;
END;
$$;

-- ── 4. Refresh pool-level rollups so dashboards match the new history ─────
UPDATE liquidity_pools p
   SET volume_24h = COALESCE(agg.vol_24h, 0)
  FROM (
    SELECT pool_id,
           SUM(CASE WHEN status = 'CONFIRMED' THEN amount_in ELSE 0 END) AS vol_24h
      FROM transactions
     WHERE tx_type = 'SWAP'
       AND created_at >= NOW() - interval '24 hours'
     GROUP BY pool_id
  ) agg
 WHERE p.id = agg.pool_id;

-- Pool-wide fee totals (X-side and Y-side accumulators)
UPDATE liquidity_pools p
   SET total_fees_collected_x = COALESCE(agg.fee_x, 0),
       total_fees_collected_y = COALESCE(agg.fee_y, 0)
  FROM (
    SELECT t.pool_id,
           SUM(CASE WHEN t.token_in_id = p2.token_x_id THEN t.fee_amount ELSE 0 END) AS fee_x,
           SUM(CASE WHEN t.token_in_id = p2.token_y_id THEN t.fee_amount ELSE 0 END) AS fee_y
      FROM transactions t
      JOIN liquidity_pools p2 ON p2.id = t.pool_id
     WHERE t.status = 'CONFIRMED'
       AND t.tx_type = 'SWAP'
     GROUP BY t.pool_id
  ) agg
 WHERE p.id = agg.pool_id;

-- ── 5. Summary row for quick verification ─────────────────────────────────
DO $$
DECLARE
  v_tx_count INT;
  v_pos_count INT;
  v_fee_count INT;
BEGIN
  SELECT COUNT(*) INTO v_tx_count FROM transactions WHERE idempotency_key LIKE 'seed-history-%';
  SELECT COUNT(*) INTO v_pos_count FROM lp_positions WHERE id::text LIKE '77000000-%';
  SELECT COUNT(*) INTO v_fee_count FROM fee_accruals
    WHERE tx_id IN (SELECT id FROM transactions WHERE idempotency_key LIKE 'seed-history-%');
  RAISE NOTICE '[seed-trading-history] inserted: transactions=%, positions=%, fee_accruals=%',
    v_tx_count, v_pos_count, v_fee_count;
END;
$$;
