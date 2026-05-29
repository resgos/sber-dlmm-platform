-- 09-seed-ohlcv-backfill.sql — populate ohlcv_candles from CONFIRMED SWAP txns.
--
-- WHY: the seed's swaps were SQL inserts that never went through Kafka, so the
-- price-oracle's SwapEventOhlcvConsumer never materialised any candles → the
-- PC-02 price chart rendered its empty state on every pool. This replays the
-- real transaction history into 1-minute candles (interval_sec=60); the
-- frontend (lib/ohlcv.ts) rolls 1m → 5m/1h/1d losslessly, so all timeframes
-- populate from this one backfill.
--
-- PRICE: the pool's Y-per-X execution price of each swap (direction-aware via
-- token_in_id vs pool.token_x_id). The seed's 03-history swaps used ~equal
-- in/out amounts (garbage price ~1) instead of real DLMM prices; in a DLMM the
-- real execution price ≈ the bin price ≈ pool.base_price, so any computed price
-- outside a sane band (0.2x–5x base_price) falls back to base_price. This keeps
-- every pool charted with correct prices (no 1→95 outlier candles) and also
-- guards numeric(30,18) overflow on pathological raw-unit ratios.
-- VOLUME = Σ amount_in in the bucket. Idempotent via ON CONFLICT DO NOTHING.

INSERT INTO ohlcv_candles
  (id, pool_id, interval_sec, open_time, open_price, high_price, low_price, close_price, volume_in, swap_count)
SELECT
  gen_random_uuid(),
  s.pool_id,
  60,
  s.bucket,
  (array_agg(s.price ORDER BY s.created_at ASC))[1]   AS open_price,
  max(s.price)                                         AS high_price,
  min(s.price)                                         AS low_price,
  (array_agg(s.price ORDER BY s.created_at DESC))[1]   AS close_price,
  sum(s.amount_in)                                     AS volume_in,
  count(*)                                             AS swap_count
FROM (
  SELECT
    r.pool_id,
    r.bucket,
    r.created_at,
    r.amount_in,
    CASE WHEN r.raw_price BETWEEN r.base_price * 0.2 AND r.base_price * 5
         THEN r.raw_price
         ELSE r.base_price          -- garbage seed-swap ratio → real DLMM price
    END AS price
  FROM (
    SELECT
      t.pool_id,
      date_trunc('minute', t.created_at) AS bucket,
      t.created_at,
      t.amount_in,
      lp.base_price,
      CASE WHEN t.token_in_id = lp.token_x_id
           THEN t.amount_out::numeric / NULLIF(t.amount_in, 0)   -- X->Y: Y per X
           ELSE t.amount_in::numeric  / NULLIF(t.amount_out, 0)  -- Y->X: Y per X
      END AS raw_price
    FROM transactions t
    JOIN liquidity_pools lp ON lp.id = t.pool_id
    WHERE t.tx_type = 'SWAP'
      AND t.status  = 'CONFIRMED'
      AND t.amount_in  > 0
      AND t.amount_out > 0
      AND t.pool_id IS NOT NULL
      AND lp.base_price > 0
  ) r
) s
WHERE s.price > 0
GROUP BY s.pool_id, s.bucket
ON CONFLICT (pool_id, interval_sec, open_time) DO NOTHING;
