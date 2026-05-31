-- ============================================================================
-- 11-seed-rescale-amounts-1e4.sql — Sprint 16 (#14): uniform platform amount scale
--
-- Adopts a single platform scale of 10^4: every token AMOUNT becomes a raw
-- integer where 1 unit = 10^-4 of a token (4 "platform decimals"). The user-ui
-- divides raw→human and multiplies human→raw at the API boundary
-- (dlmm-user-ui/src/api/scale.ts), so fractional amounts (e.g. 0.5 SBTC) are
-- now representable instead of the swap engine flooring sub-1-token output to 0
-- ("нельзя купить полбиткоина").
--
-- A UNIFORM scale leaves every price (a Y/X ratio) UNCHANGED, so this script
-- scales ONLY token quantities — never prices, fee-bps, fee-growth
-- accumulators, composition factors, percentages, ratios, decimals or bin ids.
-- It also preserves the bin invariant (liquidity = reserve_x*price + reserve_y):
-- both sides scale by 10^4. Token total/circulating supply is intentionally
-- NOT scaled (never displayed, unused in trade flows; the UI leaves it raw too).
--
-- IDEMPOTENT — guarded by _platform_seed_flags. Safe to re-run, UNLIKE the
-- non-idempotent 06/08 rescales. Apply AFTER 05-10 (see CLAUDE.md seed order).
-- ============================================================================

CREATE TABLE IF NOT EXISTS _platform_seed_flags (
    flag        VARCHAR(120) PRIMARY KEY,
    applied_at  TIMESTAMP NOT NULL DEFAULT now()
);

DO $$
DECLARE
    s CONSTANT bigint := 10000;
BEGIN
    IF EXISTS (SELECT 1 FROM _platform_seed_flags WHERE flag = 'amount_scale_1e4_v1') THEN
        RAISE NOTICE '[11] amount_scale_1e4_v1 already applied — skipping';
        RETURN;
    END IF;

    -- user token balances
    UPDATE user_balances SET available = available * s, locked = locked * s;

    -- pool aggregates: TVL, 24h volume, collected + protocol fees, swap caps
    -- (base_price / *_bps / protocol_fee_pct / volatility_accumulator untouched)
    UPDATE liquidity_pools SET
        total_tvl_x               = total_tvl_x * s,
        total_tvl_y               = total_tvl_y * s,
        volume_24h                = volume_24h * s,
        total_fees_collected_x    = total_fees_collected_x * s,
        total_fees_collected_y    = total_fees_collected_y * s,
        total_protocol_fee_x      = total_protocol_fee_x * s,
        total_protocol_fee_y      = total_protocol_fee_y * s,
        max_single_swap_nominal_x = max_single_swap_nominal_x * s,  -- NULL stays NULL
        max_single_swap_nominal_y = max_single_swap_nominal_y * s;

    -- bin reserves + liquidity + accrued fee amounts
    -- (price / composition_factor / fee_growth_* are ratios — untouched)
    UPDATE pool_bins SET
        liquidity   = liquidity * s,
        reserve_x   = reserve_x * s,
        reserve_y   = reserve_y * s,
        total_fee_x = total_fee_x * s,
        total_fee_y = total_fee_y * s;

    -- LP positions: shares, unclaimed fees, cost basis
    -- (last_fee_growth_* are ratios — untouched)
    UPDATE lp_positions SET
        total_liquidity_shares = total_liquidity_shares * s,
        unclaimed_fee_x        = unclaimed_fee_x * s,
        unclaimed_fee_y        = unclaimed_fee_y * s,
        initial_deposit_x      = initial_deposit_x * s,
        initial_deposit_y      = initial_deposit_y * s;

    UPDATE position_bins SET liquidity_shares = liquidity_shares * s;

    -- transaction amounts (fee_rate is a ratio — untouched)
    UPDATE transactions SET
        amount_in  = amount_in * s,
        amount_out = amount_out * s,
        fee_amount = fee_amount * s;

    UPDATE fee_accruals SET amount = amount * s;

    -- Spasibo loyalty: points = SSPAS units, rub_amount = SRUB credited on CONVERT
    UPDATE spasibo_operations SET points = points * s, rub_amount = rub_amount * s;

    -- YSRUB yield (ratio_micro / *_bps untouched)
    UPDATE ysrub_yield_accruals SET
        principal_at_accrual = principal_at_accrual * s,
        yield_amount         = yield_amount * s;
    UPDATE ysrub_reserve_movements SET
        srub_amount  = srub_amount * s,
        ysrub_amount = ysrub_amount * s;

    -- B2B settlement amounts (vat_rate_pct untouched)
    UPDATE b2b_settlements SET
        amount           = amount * s,
        gross_fee_amount = gross_fee_amount * s,
        vat_amount       = vat_amount * s,
        net_fee_amount   = net_fee_amount * s;

    -- OTC block trades (quoted_price_micro is a price — untouched)
    UPDATE otc_block_trades SET amount_in = amount_in * s, amount_out = amount_out * s;

    -- OHLCV candle volume (open/high/low/close prices untouched)
    UPDATE ohlcv_candles SET volume_in = volume_in * s;

    INSERT INTO _platform_seed_flags (flag) VALUES ('amount_scale_1e4_v1');
    RAISE NOTICE '[11] amount_scale_1e4_v1 applied — all token amounts x%', s;
END $$;

-- Self-check: a uniform scale must preserve the bin invariant
-- (liquidity = reserve_x*price + reserve_y). Non-fatal: warns on drift.
DO $$
DECLARE
    bad int;
BEGIN
    SELECT count(*) INTO bad FROM pool_bins
    WHERE liquidity > 0
      AND abs(liquidity - (reserve_x * price + reserve_y))
          > GREATEST(liquidity * 0.001, 100000);
    IF bad > 0 THEN
        RAISE WARNING '[11] bin-invariant: % bins drift >0.1%% post-rescale', bad;
    ELSE
        RAISE NOTICE '[11] bin-invariant OK post-rescale';
    END IF;
END $$;
