-- ============================================================================
-- 17-seed-ohlcv-carryforward.sql
--
-- Keeps pool price charts ALIVE: tops up 1-minute OHLCV candles from each
-- pool's newest candle (or a 48h bootstrap for pools with none) up to NOW(),
-- ending exactly at liquidity_pools.current_price so the chart agrees with the
-- ticker. Without this the charts freeze at the last seeded/live swap (found
-- live 2026-06-12: newest candle was 2 weeks old, several pools had none —
-- dead/empty charts on the demo).
--
-- Shape: one candle every 5 minutes (interval_sec stays 60 — the UI requests
-- 1m candles and aggregates client-side; sparse minute candles aggregate and
-- connect fine, 5-min spacing keeps row counts ~12x lower). Price path is a
-- gentle sine+noise walk whose deviation DECAYS towards "now", pinning the
-- final close to current_price. Volume per candle is a realistic slice of the
-- pool's volume_24h.
--
-- Idempotent + re-runnable (like 05-volume-refresh): each run only fills from
-- the newest existing candle forward; ON CONFLICT (pool,interval,open_time)
-- DO NOTHING guards races. Refresh a live demo's charts any time with:
--   FORCE_REAPPLY=17-seed-ohlcv-carryforward bash scripts/seed-demo.sh
-- ============================================================================

INSERT INTO ohlcv_candles
  (id, pool_id, interval_sec, open_time,
   open_price, high_price, low_price, close_price, volume_in, swap_count)
SELECT
  gen_random_uuid(),
  w.pool_id,
  60,
  w.open_time,
  round(COALESCE(LAG(w.price) OVER (PARTITION BY w.pool_id ORDER BY w.open_time), w.price), 8) AS open_price,
  round((GREATEST(COALESCE(LAG(w.price) OVER (PARTITION BY w.pool_id ORDER BY w.open_time), w.price), w.price)
        * (1 + 0.0006 * random()))::numeric, 8) AS high_price,
  round((LEAST(COALESCE(LAG(w.price) OVER (PARTITION BY w.pool_id ORDER BY w.open_time), w.price), w.price)
        * (1 - 0.0006 * random()))::numeric, 8) AS low_price,
  round(w.price, 8) AS close_price,
  GREATEST((w.vol24 / 288.0 * (0.4 + random() * 1.2))::bigint, 1) AS volume_in,
  1 + (random() * 3)::int AS swap_count
FROM (
  SELECT
    s.pool_id,
    s.gs AS open_time,
    s.vol24,
    -- Deviation scales with "how far from now" (frac: 1 at the start of the
    -- gap -> 0 at now), so the walk wanders early and converges onto the
    -- pool's current_price at the newest candle.
    (s.px * (1
             + s.frac * 0.006 * sin(extract(epoch FROM s.gs) / 3700.0)
             + s.frac * 0.003 * (random() - 0.5)))::numeric AS price,
    s.px
  FROM (
    SELECT
      b.pool_id, b.px, b.vol24, gs,
      CASE WHEN b.t_to > b.t_from
           THEN extract(epoch FROM (b.t_to - gs)) / NULLIF(extract(epoch FROM (b.t_to - b.t_from)), 0)
           ELSE 0 END AS frac
    FROM (
      SELECT
        lp.id AS pool_id,
        -- Current price = base_price stepped to the active bin (the engine's
        -- formula: base · (1 + binStep/10⁴)^(activeBin − 2²³)); liquidity_pools
        -- has no materialised current_price column.
        (lp.base_price::numeric
           * power(1.0 + lp.bin_step / 10000.0, lp.active_bin_id - 8388608))::numeric AS px,
        GREATEST(lp.volume_24h, 0)::numeric AS vol24,
        GREATEST(
          COALESCE((SELECT max(c.open_time) + INTERVAL '5 minutes'
                    FROM ohlcv_candles c
                    WHERE c.pool_id = lp.id AND c.interval_sec = 60),
                   date_trunc('minute', NOW()) - INTERVAL '48 hours'),
          date_trunc('minute', NOW()) - INTERVAL '48 hours'
        ) AS t_from,
        date_trunc('minute', NOW()) AS t_to
      FROM liquidity_pools lp
      WHERE lp.status = 'ACTIVE' AND lp.base_price > 0
    ) b
    CROSS JOIN LATERAL generate_series(date_trunc('minute', b.t_from), b.t_to, INTERVAL '5 minutes') gs
    WHERE b.t_from <= b.t_to
  ) s
) w
ON CONFLICT (pool_id, interval_sec, open_time) DO NOTHING;

-- Self-check: every active pool must now have a candle no older than 10 minutes.
DO $$
DECLARE stale int; fresh int;
BEGIN
    SELECT count(*) INTO stale FROM liquidity_pools lp
    WHERE lp.status='ACTIVE' AND lp.base_price > 0
      AND COALESCE((SELECT max(c.open_time) FROM ohlcv_candles c
                    WHERE c.pool_id = lp.id AND c.interval_sec = 60),
                   '-infinity') < NOW() - INTERVAL '10 minutes';
    SELECT count(*) INTO fresh FROM ohlcv_candles WHERE open_time > NOW() - INTERVAL '10 minutes';
    RAISE NOTICE '17-seed: pools with stale charts after run = % (expect 0); candles in the last 10min = %', stale, fresh;
END $$;
