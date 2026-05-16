# Analysis: same-pool row lock — Sprint 3 #3.10

**Author**: SA
**Date**: 2026-05-16 (Sprint 3 day 1)
**Status**: Recommendation. Implementation decision owed by Sprint 4 #4.7.
**Blocks**: Sprint 6 MM rebate (R#20 — Treasury LP dominance).

---

## The problem (from Sprint 2 k6 baseline)

Under 300 VUs all hitting the same pool (`c0000000-0000-0000-0000-000000000001`),
swap p99 was 42 s. The Hikari saturation explains a portion of that
(addressed in Sprint 3 #3.6 — pool size 20→50), but the residual
bottleneck is a **Postgres row-level lock on the pool record itself**.

Why every concurrent swap on the same pool serialises today:

1. `SwapService.swap()` runs inside `@Transactional`.
2. It mutates `liquidity_pools.active_bin_id`, `total_tvl_x`,
   `total_tvl_y`, `volume_24h` — all on the same row.
3. JPA's `save()` issues `UPDATE liquidity_pools SET ... WHERE id = ?`
   inside the transaction.
4. Postgres takes a `ROW EXCLUSIVE` lock on that row for the duration
   of the tx (~50-200 ms including external `tokenServiceClient`
   round-trips).
5. Second concurrent swap on the same pool: `SELECT ... FROM
   liquidity_pools WHERE id = ?` blocks waiting for the lock, then
   proceeds. Effectively N concurrent → N × tx_duration latency.

Same effect on `pool_bins` — `liquidity` and `reserve_x/y` mutate per
bin, but on a hot pool the active bin dominates traffic so it's
practically a single-row lock too.

This becomes critical when Sprint 4 #4.A (Sber Treasury) places 1 B+
as a dominant LP and Sprint 6 starts MM rebate-driven volume:

- **Treasury LP scenario**: every retail swap that touches the same
  pool serialises through Treasury's update path. 50 concurrent
  retail swaps → 50× latency.
- **MM scenario**: 5 MMs each quoting actively into the same hot pool
  → throughput ceiling = 1 / (tx_duration). On current numbers that's
  roughly 5-10 swaps/sec/pool — not 200.

The Sprint 1 N+1 fix and Sprint 2 outbox don't address this; the lock
is on the write path itself.

---

## Three viable approaches

### Option A — Optimistic locking with retry

Add `@Version` column to `LiquidityPool`. Reads no longer block.
Writes check the version and retry on conflict.

**Pros**:
- Minimal code change (one annotation, retry loop in SwapService)
- No schema migration beyond `ALTER TABLE liquidity_pools ADD COLUMN version BIGINT NOT NULL DEFAULT 0`
- Throughput scales linearly with **uncontended** swaps

**Cons**:
- On a hot pool with high write contention, retry rate climbs and
  effective throughput **drops** below pessimistic locking
- Tail latency unpredictable (some calls retry 3-5 times)
- Doesn't help when contention is real (Treasury + MM scenario)

**Verdict**: ✅ for the 90% case (different pools, modest concurrency).
❌ for the hot-pool case.

### Option B — Per-bin locking instead of per-pool

Move the mutable counters (`total_tvl_x/y`, `volume_24h`) off the
`liquidity_pools` row entirely. Reads use `SUM(reserve_x) FROM
pool_bins WHERE pool_id = ?` (with materialised view if needed).
Writes only touch `pool_bins` rows for the bins actually crossed —
typically 1-3 bins per swap.

**Pros**:
- Real concurrency: 22 pools × ~10 bins per swap path → effectively
  100+ simultaneous lock granularity
- Aligns with DLMM math: state IS per-bin, the pool row is just an
  index
- No retry storm risk

**Cons**:
- Schema migration is non-trivial (drop columns, add materialised
  view, update all read paths)
- `volume_24h` becomes a rolling computation, not a counter (or moves
  to ClickHouse stream)
- `active_bin_id` still on pool row — but only updates when a swap
  crosses out of the current bin, which is rare; could use Option A
  (optimistic) just for this column

**Verdict**: ✅ correct long-term design. Effort: ~2 sprints worth of
work properly.

### Option C — Per-pool sharding (horizontal)

Run pool-engine pods affinitised by pool_id (consistent hashing).
Each pod holds the canonical state for "its" pools in memory + WAL
to Postgres. Reads route to the owning pod via gateway hash.

**Pros**:
- Infinite horizontal scale (more pods → more pools served)
- Sub-ms write latency (in-memory state, async WAL)

**Cons**:
- Massive complexity: pod-affinity routing in gateway, state recovery
  on pod restart, split-brain risk on partition
- Doesn't help a single hot pool (one pod still serialises its own state)
- Architectural rewrite, 6+ months

**Verdict**: ❌ premature for current scale. Reconsider 2027 H2
if a single pool ever hits 1k+ TPS sustained.

---

## Recommendation

**Sprint 4 #4.7**: Implement Option A (optimistic locking) as the
short-term fix. Closes the immediate Treasury LP scenario (R#20)
with minimal risk. ~3 days work.

**Sprint 5–6 spike**: Spike on Option B (per-bin granularity) as
a 1-week design + prototype. Measure retry rate under the post-A
load profile. If retries exceed 5% on the hottest pool, escalate
Option B to a full sprint in Q4.

**Sprint pre-MM-rebate (6 or 7)**: Decision gate. Option A holding?
ship MM rebate. Retry rate too high? Option B before MM goes live.

Option C is icebox — `docs/SPRINT-PLAN.md` parking lot until
sustained single-pool TPS proves it's needed.

---

## What blocks what

| Blocks | Owner |
|---|---|
| Sprint 4 #4.A (Sber Treasury onboarding as LP) — without lock fix, Treasury swaps could starve retail | Backend lead |
| Sprint 6 (MM rebate program) — MM expect high concurrency on hot pools | Backend lead |
| Sprint 7 (money market YSRUB) — yield distribution writes contend with daily traffic | Backend lead |

---

## Test scenarios to verify the fix

When Sprint 4 #4.7 lands:

1. **k6 single-pool concurrency**: 50 VUs all hitting pool 001,
   500 swaps total. Pre-fix: 96% errors @ p99 42s. Post-fix
   (Option A): retry rate < 10%, p99 < 2s.
2. **k6 multi-pool**: 50 VUs spread across 22 pools. Both fixes
   should show near-linear scaling (almost no retry).
3. **Conflict pattern check**: query Postgres `pg_stat_activity`
   under load — should NOT show waiting LOCKS, only retries in
   application logs.

---

*Recommendation: Option A in Sprint 4. Owner: Backend lead. Re-eval
after MM rebate goes live.*
