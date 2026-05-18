-- ============================================================================
-- Sber DLMM Platform — SberSpasibo loyalty integration seed (Sprint 5 #5.1 + #5.2)
-- ----------------------------------------------------------------------------
-- Adds the SSPAS (СберСпасибо) loyalty token, an SSPAS/SRUB pool with
-- zero base fee (retail-grade conversion), bin liquidity from a Spasibo
-- treasury account, and demo balances for seed users so the conversion
-- widget on user-ui has data to show on first launch.
--
-- Loaded after 02-extended-assets.sql by postgres docker-entrypoint-initdb.d
-- file-name ordering. All inserts ON CONFLICT DO NOTHING so reapplying is safe.
-- ============================================================================

-- ─── SSPAS token (UTILITY classification per 259-ФЗ memo §2.4) ─────────────
-- Decimals=2 because real Spasibo баллы are whole points (no fractional);
-- we keep 2 decimal precision for future "0.5 balla" cashback support.
-- Mint authority = admin (in production = SberSpasibo BU service account).
INSERT INTO tokens (id, name, symbol, decimals, total_supply, max_supply, token_type, underlying_asset, price_oracle_id, mintable, burnable, active, created_by) VALUES
  ('b0000000-0000-0000-0000-000000000201', 'СберСпасибо балл', 'SSPAS', 2, 100000000000, 0, 'UTILITY', 'SBER:LOYALTY', NULL, true, true, true, 'a0000000-0000-0000-0000-000000000001')
ON CONFLICT DO NOTHING;

-- ─── SSPAS/SRUB pool — retail conversion, base_fee_bps=0 ───────────────────
-- Pool x = SSPAS, y = SRUB. base_price = 0.01 (1 SSPAS = 0.01 SRUB initially,
-- production Spasibo programs run at ~1 балл = 0.01 ₽; SberSpasibo BU adjusts).
-- bin_step = 1 bp (very tight — this isn't a speculative market).
-- protocol_fee_pct = 0 — retail conversion never feeds protocol fees.
INSERT INTO liquidity_pools (id, token_x_id, token_y_id, bin_step, base_fee_bps, max_variable_fee_bps, volatility_accumulator, decay_period_seconds, active_bin_id, base_price, protocol_fee_pct, total_tvl_x, total_tvl_y, volume_24h, total_fees_collected_x, total_fees_collected_y, status, created_by, created_at, version) VALUES
  ('c0000000-0000-0000-0000-000000000201',
   'b0000000-0000-0000-0000-000000000201', -- SSPAS
   'b0000000-0000-0000-0000-000000000001', -- SRUB (from init-db.sql seed)
   1,    -- bin_step (1 bp tight ladder)
   0,    -- base_fee_bps — RETAIL: zero conversion fee promise
   0,    -- max_variable_fee_bps
   0,    -- volatility_accumulator
   3600, -- decay_period_seconds
   8388608, -- active_bin_id (Meteora-style middle bin)
   0.01, -- base_price: 1 SSPAS = 0.01 SRUB
   0,    -- protocol_fee_pct: retail = 0
   100000000000, -- total_tvl_x (1B SSPAS seed liquidity from Spasibo treasury)
   1000000000,   -- total_tvl_y (10M SRUB seed liquidity)
   0, 0, 0,
   'ACTIVE',
   'a0000000-0000-0000-0000-000000000001',
   CURRENT_TIMESTAMP,
   0)
ON CONFLICT DO NOTHING;

-- ─── Pool bins for the SSPAS/SRUB pool ─────────────────────────────────────
-- Reuses the same geometric-ladder pattern as extended-assets.sql but
-- targets just one pool. Active bin = 50/50, +/- 10 bins above/below.
DO $$
DECLARE
  pool_id_const UUID := 'c0000000-0000-0000-0000-000000000201';
  bin_offset INT;
  bin_id INT;
  price NUMERIC(36,18);
  liquidity BIGINT;
  reserve_x BIGINT;
  reserve_y BIGINT;
  base_p NUMERIC(36,18);
  step_bps INT;
  active_b INT;
  tvl_x BIGINT;
  tvl_y BIGINT;
BEGIN
  SELECT base_price, bin_step, active_bin_id, total_tvl_x, total_tvl_y
    INTO base_p, step_bps, active_b, tvl_x, tvl_y
    FROM liquidity_pools WHERE id = pool_id_const;
  IF base_p IS NULL THEN
    RETURN;  -- pool seed didn't insert (probably already existed)
  END IF;
  FOR bin_offset IN -10..10 LOOP
    bin_id := active_b + bin_offset;
    price := base_p * power(1.0 + (step_bps::NUMERIC / 10000.0), bin_offset);
    IF bin_offset < 0 THEN
      reserve_x := 0;
      reserve_y := (tvl_y / 11)::BIGINT;
      liquidity := reserve_y;
    ELSIF bin_offset > 0 THEN
      reserve_x := (tvl_x / 11)::BIGINT;
      reserve_y := 0;
      liquidity := reserve_x;
    ELSE
      reserve_x := (tvl_x / 22)::BIGINT;
      reserve_y := (tvl_y / 22)::BIGINT;
      liquidity := reserve_x + reserve_y;
    END IF;
    INSERT INTO pool_bins (pool_id, bin_id, price, liquidity, reserve_x, reserve_y, composition_factor, total_fee_x, total_fee_y, fee_growth_x, fee_growth_y)
    VALUES (pool_id_const, bin_id, price, liquidity, reserve_x, reserve_y, 0.5, 0, 0, 0, 0)
    ON CONFLICT DO NOTHING;
  END LOOP;
END $$;

-- ─── Demo balances: each seed user starts with some Spasibo points ─────────
INSERT INTO user_balances (user_id, token_id, available, locked) VALUES
  -- admin: 1M SSPAS (treasury pool also gets liquidity above)
  ('a0000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000201', 100000000, 0),
  -- ivanov: 50k SSPAS — typical mid-tier loyalty member
  ('a0000000-0000-0000-0000-000000000002', 'b0000000-0000-0000-0000-000000000201',   5000000, 0),
  -- sidorova: 12.5k SSPAS — entry-tier
  ('a0000000-0000-0000-0000-000000000003', 'b0000000-0000-0000-0000-000000000201',   1250000, 0)
ON CONFLICT DO NOTHING;
