#!/usr/bin/env bash
#
# seed-demo.sh — bring a freshly-booted stack to the local DEMO data state.
#
# The Postgres entrypoint auto-runs init-db.sql + 02/03/04 on a FRESH volume.
# Seeds 05-12 touch tables the backend services create via Liquibase AT BOOT, so
# they cannot run at the Postgres entrypoint (too early) — this script applies
# them AFTER the stack is up.
#
# A per-step marker table (demo_seed_log) makes the script idempotent and
# resumable and — crucially — guarantees the NON-idempotent steps run at most
# ONCE: 06-seed-tvl-rescale and 08-seed-balance-rescale DIVIDE existing values,
# so a second application would corrupt the data. Safe to re-run; applied steps
# are skipped.
#
# Usage:
#   bash scripts/seed-demo.sh                                  # apply not-yet-applied seeds
#   PG_CONTAINER=dlmm-postgres bash scripts/seed-demo.sh       # custom container name
#   FORCE_REAPPLY=05-seed-volume-refresh bash scripts/seed-demo.sh   # re-run one step (expert)
#
# DB creds are read from docker/.env (DB_USER / DB_PASSWORD), overridable via env.
#
set -euo pipefail
cd "$(dirname "$0")/.."

PG="${PG_CONTAINER:-dlmm-postgres}"
DB="${DB_NAME:-dlmm}"
[ -f docker/.env ] && { set -a; . docker/.env; set +a; }
DBU="${DB_USER:-dlmm}"
DBPW="${DB_PASSWORD:-}"

# Ordered post-entrypoint seeds (05-12, 14). 06 & 08 DIVIDE → must run exactly once.
# 13 is a retired no-op (replaced by the per-bin fee model) — not listed. 14 seeds
# per-bin fee growth so the demo has claimable fees; idempotent (forced SET).
SEEDS=(
  05-seed-volume-refresh
  06-seed-tvl-rescale
  07-seed-fix-backend-bugs
  08-seed-balance-rescale
  09-seed-ohlcv-backfill
  10-seed-reconcile-bin-invariant
  11-seed-rescale-amounts-1e4
  12-seed-farming-rewards
  14-seed-bin-fee-growth
)

psqlq() { docker exec -i -e PGPASSWORD="$DBPW" "$PG" psql -U "$DBU" -d "$DB" "$@"; }

# 0) container up?
if ! docker ps --format '{{.Names}}' | grep -qx "$PG"; then
  echo "✗ container '$PG' is not running — start the stack first (docker compose up -d)" >&2
  exit 1
fi

# 1) wait until Liquibase has built the schema — including the per-bin fee-growth
#    checkpoint columns (changeset 014) that 14-seed-bin-fee-growth needs.
echo "▶ waiting for the Liquibase-managed schema (position_bins.fee_growth_checkpoint) …"
for i in $(seq 1 60); do
  if [ "$(psqlq -tAc "select exists(select 1 from information_schema.columns where table_name='position_bins' and column_name='fee_growth_checkpoint_x')" 2>/dev/null | tr -d '[:space:]')" = "t" ]; then
    echo "  schema ready"
    break
  fi
  if [ "$i" -eq 60 ]; then
    echo "✗ schema not ready after 120s — are all services up & healthy?" >&2
    exit 2
  fi
  sleep 2
done

# 2) per-step marker table
psqlq -q -c "CREATE TABLE IF NOT EXISTS demo_seed_log (step text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now());"

# 3) apply each seed once, in order
applied=0
for s in "${SEEDS[@]}"; do
  f="docker/$s.sql"
  if [ ! -f "$f" ]; then echo "✗ missing $f" >&2; exit 3; fi
  done_already="$(psqlq -tAc "select 1 from demo_seed_log where step='$s'" 2>/dev/null | tr -d '[:space:]')"
  if [ "$done_already" = "1" ] && [ "${FORCE_REAPPLY:-}" != "$s" ]; then
    echo "▶ $s — already applied, skip"
    continue
  fi
  echo "▶ applying $s …"
  psqlq -v ON_ERROR_STOP=1 -q < "$f"
  psqlq -q -c "INSERT INTO demo_seed_log(step) VALUES ('$s') ON CONFLICT (step) DO UPDATE SET applied_at = now();"
  applied=$((applied + 1))
done

# 4) summary
echo "▶ demo data summary:"
psqlq -tA -c "
  select '  pools (active/total) : '||count(*) filter (where status='ACTIVE')||' / '||count(*) from liquidity_pools;
  select '  lp_positions         : '||count(*) from lp_positions;
  select '  transactions         : '||count(*) from transactions;
  select '  farming reward configs: '||count(*) from pool_rewards_config;
  select '  ivanov balance rows  : '||count(*) from user_balances where user_id='a0000000-0000-0000-0000-000000000002';
"
echo "✓ seed-demo complete ($applied step(s) applied this run)"
echo "  NB: 05-seed-volume-refresh ages out after ~24h (the scheduler drops swaps from the 24h window)."
echo "      Refresh a live demo's volume/APY with:  FORCE_REAPPLY=05-seed-volume-refresh bash scripts/seed-demo.sh"
