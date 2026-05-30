-- F-08 (UX-FINDINGS 2026-05-26) — rescale seed TVL к realistic pilot numbers.
--
-- Без этого пулы показывают "163 трлн ₽" что (а) вызывает сомнения у PO
-- ("откуда 163 триллиона?"), (б) делает realistic 5M-50M ₽ pilot
-- transactions выглядеть как "0%" доли пула.
--
-- Strategy: divide TVL by 10000 — кладёт пулы в диапазон 5M-200M ₽,
-- соответствует pilot expectation для треasury MM. Position-level
-- liquidity_shares + bin reserves тоже рескейлятся пропорционально
-- так что pool math (share % calculation, swap routing) не ломается.
--
-- Runs after 05-seed-volume-refresh.sql so volume_24h гётся повторно
-- (volume в той же шкале что и TVL, нужен propusca тоже).
--
-- IDEMPOTENCY (Sprint 10 fix): this rescale divides by a constant and is
-- NON-reversible. The per-row `WHERE … > 1e8` filters do NOT make a re-run
-- safe — a pool/bin/balance that stays above the threshold after one pass
-- (e.g. SUSDT/SRUB at 1.9e10 after one /10000) gets divided AGAIN, collapsing
-- the data ~10^8×. So the whole script is now guarded by a one-shot
-- `seed_markers` row: a second `psql -f` (or a re-run of the PRE-DEMO STEP 0b
-- loop) is a NO-OP. A fresh `docker compose down -v` wipes the marker, so the
-- normal post-reset re-seed still applies it.

CREATE TABLE IF NOT EXISTS seed_markers (
    name       TEXT PRIMARY KEY,
    applied_at TIMESTAMP NOT NULL DEFAULT NOW()
);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM seed_markers WHERE name = '06-seed-tvl-rescale') THEN
        RAISE NOTICE '06-seed-tvl-rescale already applied — skipping (non-idempotent rescale)';
        RETURN;
    END IF;

    -- Step 1: rescale pool-level aggregates
    UPDATE liquidity_pools SET
        total_tvl_x = GREATEST(total_tvl_x / 10000, 1),
        total_tvl_y = GREATEST(total_tvl_y / 10000, 1),
        volume_24h = GREATEST(volume_24h / 10000, 1),
        total_fees_collected_x = total_fees_collected_x / 10000,
        total_fees_collected_y = total_fees_collected_y / 10000,
        total_protocol_fee_x = total_protocol_fee_x / 10000,
        total_protocol_fee_y = total_protocol_fee_y / 10000
    WHERE total_tvl_y > 100000000;  -- safety: only rescale the unrealistically-high entries

    -- Step 2: rescale bin reserves so pool depth math остаётся consistent
    UPDATE pool_bins SET
        reserve_x = reserve_x / 10000,
        reserve_y = reserve_y / 10000,
        liquidity = liquidity / 10000
    WHERE liquidity > 100000000;

    -- Step 3: rescale position-level shares (LP share calculations stay
    -- mathematically same since BOTH sides scaled by same factor).
    -- Note: current_value_x/y are SERVICE-COMPUTED (no DB column) — derive
    -- on-the-fly from liquidity_shares × bin_reserves, so no rescale here.
    UPDATE lp_positions SET
        total_liquidity_shares = GREATEST(total_liquidity_shares / 10000, 1),
        initial_deposit_x = initial_deposit_x / 10000,
        initial_deposit_y = initial_deposit_y / 10000,
        unclaimed_fee_x = unclaimed_fee_x / 10000,
        unclaimed_fee_y = unclaimed_fee_y / 10000
    WHERE total_liquidity_shares > 100000000;

    -- Step 4: rescale per-bin position allocation
    UPDATE position_bins SET
        liquidity_shares = GREATEST(liquidity_shares / 10000, 1)
    WHERE liquidity_shares > 100000000;

    -- Step 5: rescale transaction amounts (historical swaps)
    UPDATE transactions SET
        amount_in = amount_in / 10000,
        amount_out = amount_out / 10000,
        fee_amount = COALESCE(fee_amount, 0) / 10000
    WHERE amount_in > 100000000;

    INSERT INTO seed_markers(name) VALUES ('06-seed-tvl-rescale');
    RAISE NOTICE '06-seed-tvl-rescale applied';
END $$;
