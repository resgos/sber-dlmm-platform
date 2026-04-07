-- ============================================================================
-- Sber DLMM Platform - Database Initialization
-- ============================================================================

-- ─── users (user-service) ───────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sber_id VARCHAR(255) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    phone VARCHAR(20) NOT NULL,
    first_name VARCHAR(50) NOT NULL,
    last_name VARCHAR(50) NOT NULL,
    kyc_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_users_sber_id ON users (sber_id);
CREATE INDEX IF NOT EXISTS idx_users_email ON users (email);
CREATE INDEX IF NOT EXISTS idx_users_kyc_status ON users (kyc_status);
CREATE INDEX IF NOT EXISTS idx_users_role ON users (role);

-- ─── tokens (token-service) ─────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    symbol VARCHAR(10) UNIQUE NOT NULL,
    decimals INT NOT NULL DEFAULT 8,
    total_supply BIGINT NOT NULL DEFAULT 0,
    max_supply BIGINT NOT NULL DEFAULT 0,
    token_type VARCHAR(30) NOT NULL,
    underlying_asset VARCHAR(255),
    price_oracle_id VARCHAR(255),
    mintable BOOLEAN NOT NULL DEFAULT false,
    burnable BOOLEAN NOT NULL DEFAULT false,
    active BOOLEAN NOT NULL DEFAULT true,
    created_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_tokens_symbol ON tokens (symbol);
CREATE INDEX IF NOT EXISTS idx_tokens_token_type ON tokens (token_type);
CREATE INDEX IF NOT EXISTS idx_tokens_active ON tokens (active);
CREATE INDEX IF NOT EXISTS idx_tokens_created_by ON tokens (created_by);

-- ─── user_balances (token-service) ──────────────────────────────────────────

CREATE TABLE IF NOT EXISTS user_balances (
    user_id UUID NOT NULL,
    token_id UUID NOT NULL,
    available BIGINT NOT NULL DEFAULT 0,
    locked BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, token_id)
);

CREATE INDEX IF NOT EXISTS idx_user_balances_user_id ON user_balances (user_id);
CREATE INDEX IF NOT EXISTS idx_user_balances_token_id ON user_balances (token_id);

-- ─── liquidity_pools (pool-engine) ──────────────────────────────────────────

CREATE TABLE IF NOT EXISTS liquidity_pools (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token_x_id UUID NOT NULL,
    token_y_id UUID NOT NULL,
    bin_step INT NOT NULL,
    base_fee_bps INT NOT NULL,
    max_variable_fee_bps INT NOT NULL DEFAULT 300,
    volatility_accumulator INT NOT NULL DEFAULT 0,
    decay_period_seconds INT NOT NULL DEFAULT 600,
    active_bin_id INT NOT NULL,
    base_price DECIMAL(30,18) NOT NULL,
    protocol_fee_pct INT NOT NULL DEFAULT 20,
    total_tvl_x BIGINT NOT NULL DEFAULT 0,
    total_tvl_y BIGINT NOT NULL DEFAULT 0,
    volume_24h BIGINT NOT NULL DEFAULT 0,
    total_fees_collected_x BIGINT NOT NULL DEFAULT 0,
    total_fees_collected_y BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_by UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (token_x_id, token_y_id, bin_step)
);

CREATE INDEX IF NOT EXISTS idx_liquidity_pools_token_x_id ON liquidity_pools (token_x_id);
CREATE INDEX IF NOT EXISTS idx_liquidity_pools_token_y_id ON liquidity_pools (token_y_id);
CREATE INDEX IF NOT EXISTS idx_liquidity_pools_status ON liquidity_pools (status);
CREATE INDEX IF NOT EXISTS idx_liquidity_pools_created_by ON liquidity_pools (created_by);

-- ─── pool_bins (pool-engine) ────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS pool_bins (
    pool_id UUID NOT NULL,
    bin_id INT NOT NULL,
    price DECIMAL(30,18) NOT NULL,
    liquidity BIGINT NOT NULL DEFAULT 0,
    reserve_x BIGINT NOT NULL DEFAULT 0,
    reserve_y BIGINT NOT NULL DEFAULT 0,
    composition_factor DECIMAL(30,18) NOT NULL DEFAULT 0,
    total_fee_x BIGINT NOT NULL DEFAULT 0,
    total_fee_y BIGINT NOT NULL DEFAULT 0,
    fee_growth_x BIGINT NOT NULL DEFAULT 0,
    fee_growth_y BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (pool_id, bin_id)
);

CREATE INDEX IF NOT EXISTS idx_pool_bins_pool_id ON pool_bins (pool_id);

-- ─── lp_positions (pool-engine) ─────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS lp_positions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    pool_id UUID NOT NULL,
    bin_range_min INT NOT NULL,
    bin_range_max INT NOT NULL,
    strategy VARCHAR(20) NOT NULL,
    total_liquidity_shares BIGINT NOT NULL DEFAULT 0,
    unclaimed_fee_x BIGINT NOT NULL DEFAULT 0,
    unclaimed_fee_y BIGINT NOT NULL DEFAULT 0,
    last_fee_growth_x BIGINT NOT NULL DEFAULT 0,
    last_fee_growth_y BIGINT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_lp_positions_user_id ON lp_positions (user_id);
CREATE INDEX IF NOT EXISTS idx_lp_positions_pool_id ON lp_positions (pool_id);
CREATE INDEX IF NOT EXISTS idx_lp_positions_is_active ON lp_positions (is_active);
CREATE INDEX IF NOT EXISTS idx_lp_positions_user_pool ON lp_positions (user_id, pool_id);

-- ─── position_bins (pool-engine) ────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS position_bins (
    position_id UUID NOT NULL,
    bin_id INT NOT NULL,
    liquidity_shares BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (position_id, bin_id)
);

CREATE INDEX IF NOT EXISTS idx_position_bins_position_id ON position_bins (position_id);

-- ─── transactions (transaction-service) ─────────────────────────────────────

CREATE TABLE IF NOT EXISTS transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tx_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'CREATED',
    user_id UUID NOT NULL,
    pool_id UUID,
    token_in_id UUID,
    amount_in BIGINT,
    token_out_id UUID,
    amount_out BIGINT,
    fee_amount BIGINT NOT NULL DEFAULT 0,
    fee_rate DECIMAL(10,6),
    bins_crossed INT NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(255) UNIQUE,
    metadata TEXT,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_transactions_user_id ON transactions (user_id);
CREATE INDEX IF NOT EXISTS idx_transactions_pool_id ON transactions (pool_id);
CREATE INDEX IF NOT EXISTS idx_transactions_tx_type ON transactions (tx_type);
CREATE INDEX IF NOT EXISTS idx_transactions_status ON transactions (status);
CREATE INDEX IF NOT EXISTS idx_transactions_idempotency_key ON transactions (idempotency_key);
CREATE INDEX IF NOT EXISTS idx_transactions_created_at ON transactions (created_at);
CREATE INDEX IF NOT EXISTS idx_transactions_user_status ON transactions (user_id, status);

-- ─── fee_accruals (fee-service) ─────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS fee_accruals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    position_id UUID NOT NULL,
    pool_id UUID NOT NULL,
    user_id UUID NOT NULL,
    token_id UUID NOT NULL,
    amount BIGINT NOT NULL,
    tx_id UUID,
    claimed BOOLEAN NOT NULL DEFAULT false,
    accrued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claimed_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_fee_accruals_position_id ON fee_accruals (position_id);
CREATE INDEX IF NOT EXISTS idx_fee_accruals_pool_id ON fee_accruals (pool_id);
CREATE INDEX IF NOT EXISTS idx_fee_accruals_user_id ON fee_accruals (user_id);
CREATE INDEX IF NOT EXISTS idx_fee_accruals_token_id ON fee_accruals (token_id);
CREATE INDEX IF NOT EXISTS idx_fee_accruals_claimed ON fee_accruals (claimed);
CREATE INDEX IF NOT EXISTS idx_fee_accruals_user_claimed ON fee_accruals (user_id, claimed);

-- ─── price_feeds (price-oracle) ─────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS price_feeds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_symbol VARCHAR(20) NOT NULL,
    source VARCHAR(50) NOT NULL,
    current_price DECIMAL(30,18) NOT NULL,
    twap_price DECIMAL(30,18) NOT NULL,
    price_change_24h_pct DECIMAL(10,4) NOT NULL DEFAULT 0,
    updated_at_epoch_ms BIGINT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_price_feeds_asset_symbol ON price_feeds (asset_symbol);
CREATE INDEX IF NOT EXISTS idx_price_feeds_source ON price_feeds (source);
CREATE INDEX IF NOT EXISTS idx_price_feeds_asset_source ON price_feeds (asset_symbol, source);

-- ─── price_history (price-oracle) ───────────────────────────────────────────

CREATE TABLE IF NOT EXISTS price_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    price_feed_id UUID NOT NULL,
    price DECIMAL(30,18) NOT NULL,
    timestamp_epoch_ms BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_price_history_price_feed_id ON price_history (price_feed_id);
CREATE INDEX IF NOT EXISTS idx_price_history_timestamp ON price_history (timestamp_epoch_ms);
CREATE INDEX IF NOT EXISTS idx_price_history_feed_timestamp ON price_history (price_feed_id, timestamp_epoch_ms);

-- ─── notifications (notification-service) ───────────────────────────────────

CREATE TABLE IF NOT EXISTS notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    type VARCHAR(30) NOT NULL,
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    payload TEXT,
    read BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_notifications_user_id ON notifications (user_id);
CREATE INDEX IF NOT EXISTS idx_notifications_type ON notifications (type);
CREATE INDEX IF NOT EXISTS idx_notifications_read ON notifications (read);
CREATE INDEX IF NOT EXISTS idx_notifications_user_read ON notifications (user_id, read);
CREATE INDEX IF NOT EXISTS idx_notifications_created_at ON notifications (created_at);
