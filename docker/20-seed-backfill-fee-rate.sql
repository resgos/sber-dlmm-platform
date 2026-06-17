-- 20-seed-backfill-fee-rate.sql
--
-- fee_rate on SWAP transactions was always 0.00. Root cause: the fee_rate
-- numeric(38,2) column cannot hold the consumer's intended *ratio*
-- (fee/amountIn ≈ 0.001–0.003 rounds to 0.00 at scale 2), so every swap row —
-- seeded and live alike — recorded fee_rate = 0.
--
-- The SwapEventConsumer now stores the effective fee rate in BASIS POINTS
-- (fee/amountIn × 10000), the platform's fee unit (baseFeeBps/estimatedFeeBps),
-- which fits scale-2 cleanly. This backfills the historical/seeded swap rows to
-- the same bps unit so the demo's transaction ledger shows real fee rates.
--
-- Idempotent: recomputed from the immutable fee_amount / amount_in — re-running
-- yields the same value. Liquidity/claim rows keep fee_rate = 0 (no swap fee).

UPDATE transactions
SET fee_rate = ROUND(fee_amount * 10000.0 / amount_in, 2)
WHERE tx_type = 'SWAP'
  AND amount_in > 0
  AND fee_amount > 0
  AND (fee_rate IS NULL OR fee_rate <> ROUND(fee_amount * 10000.0 / amount_in, 2));

DO $$
DECLARE n_zero bigint;
BEGIN
  SELECT count(*) INTO n_zero
  FROM transactions
  WHERE tx_type = 'SWAP' AND amount_in > 0 AND fee_amount > 0
    AND (fee_rate IS NULL OR fee_rate = 0);
  RAISE NOTICE '20-seed-backfill-fee-rate: % swap rows still have zero fee_rate (expected 0)', n_zero;
END $$;
