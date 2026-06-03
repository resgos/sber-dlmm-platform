-- ============================================================================
-- 16-seed-fix-tx-timestamps.sql
--
-- Restores the temporal invariant  created_at <= updated_at <= confirmed_at  on the
-- seeded transaction ledger (arch-audit P0-2).
--
-- The trading-history seed (03-seed-trading-history) inserts historical swaps with
-- created_at defaulting to NOW() while confirmed_at is set to the OLD swap time, so
-- created_at > confirmed_at — "confirmed before created", which is impossible and
-- breaks event-time ordering / reconciliation. For an already-confirmed row the
-- settlement time is the truth, so align the insert/processing stamps to it.
--
-- Idempotent: once created = updated = confirmed the WHERE no longer matches.
-- Runs post-boot via seed-demo.sh (the 03-seed runs at the Postgres entrypoint).
-- ============================================================================

-- Confirmed rows: collapse created/updated onto the settlement time.
UPDATE transactions
SET created_at = confirmed_at,
    updated_at = confirmed_at
WHERE confirmed_at IS NOT NULL
  AND (created_at > confirmed_at OR updated_at > confirmed_at OR created_at > updated_at);

-- Pending rows (no settlement yet): updated must not precede created.
UPDATE transactions
SET updated_at = created_at
WHERE confirmed_at IS NULL
  AND updated_at < created_at;

DO $$
DECLARE bad int;
BEGIN
    SELECT count(*) INTO bad FROM transactions
    WHERE (confirmed_at IS NOT NULL AND (created_at > confirmed_at OR updated_at > confirmed_at OR created_at > updated_at))
       OR (confirmed_at IS NULL AND updated_at < created_at);
    RAISE NOTICE '16-seed: transactions still violating created<=updated<=confirmed = % (expect 0)', bad;
END $$;
