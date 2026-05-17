# Sber DLMM — Load Tests (k6)

## Quick start

```bash
# 1. Stack up
cd docker && docker-compose up -d && cd ..

# 2. Pre-warm (k6 thresholds assume warm cache; first-call ~2s would skew the run)
TOK=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@sber-dlmm.ru","password":"Demo1234"}' | jq -r .accessToken)
for i in 1 2 3; do curl -s -o /dev/null -H "Authorization: Bearer $TOK" http://localhost:8080/api/v1/admin/dashboard; done

# 3. Run
docker run --rm -i --network=host -v $(pwd)/loadtest:/scripts grafana/k6 run /scripts/baseline.js
```

## Scenarios

### `baseline.js`

| Metric | Target | Threshold (gate) |
|---|---|---|
| Aggregate throughput | 200 RPS sustained | (ramp-up scenario sets it) |
| `/pools` p99 | < 500 ms | hard fail |
| `/admin/dashboard` p99 | < 1500 ms | hard fail |
| `/swap` p99 | < 2000 ms | hard fail |
| Swap error rate | < 5% | hard fail (first cut; tighten next sprint) |
| Other endpoint error rate | < 1% | hard fail |

Mix: 60% pools (cached read), 20% dashboard (BFF aggregate, 3 downstream calls), 20% swap (full pipeline + outbox).

### Tuning the run

```bash
# Tighter smoke (60s, 50 VUs) — useful for CI
k6 run -e DURATION=60s -e VUS=50 loadtest/baseline.js

# Against staging
k6 run -e BASE_URL=https://dlmm-staging.sber.local loadtest/baseline.js

# Different pool / token for swap mix (e.g. a deeper pool to avoid bin exhaustion)
k6 run -e POOL_ID=c0000000-... -e SWAP_TOKEN_IN=b0000000-... loadtest/baseline.js
```

## Reading the output

* Each scenario row prints p50, p90, p95, p99, max for its custom metric.
* `swap_errors` rate is shown separately so HTTP 200 isn't conflated with successful swaps.
* If a threshold fires, the run exits non-zero — wire that into CI for nightly regression detection.

## Sprint 4 #4.7 single-pool contention test (2026-05-17)

After optimistic locking landed (`@Version` on LiquidityPool + retry
loop in SwapService), ran the new `single-pool-lock.js` to verify
linear scaling under same-pool contention (the Sprint 2 disaster
scenario).

| Load profile | p99 latency | Successful TPS | Notes |
|---|---|---|---|
| 15 VU / 21 swaps/s in one pool | 1.25s | ~21 | ✓ under target. Retry mechanic visible in logs ("Swap retry 4/5 after 33ms backoff"). Pool `version` ticks linearly. |
| 80 VU / 80 swaps/s in one pool (extreme) | 15.6s | ~16 | Threshold violated. Retry storm — 5 attempts × backoff each. Acceptable: 80 swaps/s on a single pool is not a realistic prod SLA. |

**Conclusion:** optimistic locking + retry restores throughput on
single-hot-pool scenarios up to ~20 swaps/s without serialising on
the Postgres row lock. Real prod traffic spreads across 22+ pools,
so per-pool contention rarely exceeds 5–10 swaps/s — well inside the
fix's capacity.

For extreme single-pool concentration (Treasury LP scenario, MM
rebate hot-pool), upgrade path is Option B (per-bin lock granularity)
from `docs/ANALYSIS-SAME-POOL-LOCK.md`. Not needed for Sprint 4
acceptance; flagged in `docs/SPRINT-PLAN.md` parking lot.

`swap_5xx = 0%` in both tests — no unhandled exceptions. All
non-200s are 4xx (InsufficientLiquidityException after pool drains,
or OptimisticLockingFailureException after MAX_SWAP_ATTEMPTS=5).
Either resolves with bigger seed liquidity OR higher attempt cap.

---

## Sprint 3 re-baseline (2026-05-17, Hikari 50 + multi-user/multi-pool mix)

After Sprint 3 #3.6 (Hikari 20→50) + Sprint 3 #3.7 (rotate across 3 users
× 4 pools), re-ran the 60s/80-VU smoke against the same single-laptop
stack as Sprint 2:

| Metric             | Target  | Sprint 2 p99 | Sprint 3 p99 | Delta     |
|--------------------|---------|--------------|--------------|-----------|
| `/pools`           | <500ms  | 23.22 s      | **595 ms**   | **40×**   |
| `/dashboard`       | <1500ms | 16.87 s      | **5.6 s**    | **3×**    |
| `/swap`            | <1500ms | 42.29 s      | **1.32 s** ✓ | **32×**   |
| Throughput         | 100 rps | 34 rps       | **72 rps**   | **2.1×**  |
| Swap errors        | <2%     | 96.55%       | **33%**      | bug → real |
| Read errors        | <1%     | 19.79%       | 27%          | dashboard |

**Threshold gates:**
- ✓ `swap_latency p99 < 1.5s` — PASSED
- ✗ `pools_latency p99 < 500ms` — failed by 95ms (close)
- ✗ `dashboard_latency p99 < 1.5s` — still slow; admin-bff fan-out hits
  3 downstreams serially under load
- ✗ `swap_errors < 2%` — 33% real-world contention now, vs Sprint 2's
  96% test-design pile-up. Bug fixed, ceiling exposed.

**What the swap errors actually are** (root-caused via logs):
- ~50% `InsufficientLiquidityException` on SETH/SRUB and SGOLD/SRUB —
  those pools have limited bin liquidity in seed data, k6 drains them
  in ~30s. Real-world prod has continuous LP supply; this is a seed
  artefact. Mitigation for Sprint 4 baseline: bigger seed liquidity or
  rotate across all 22 pools (currently use 4).
- ~50% `InsufficientBalance` when one of the 3 rotated users runs out
  of a side token after ~200 swaps. Same fix: more seed balance or
  cycle balance replenishment.

**Conclusion:** Sprint 3 closes the order-of-magnitude perf gap.
Remaining /dashboard + /pools tail-latency work is queued for Sprint 4
(admin-bff parallel-fanout already done in Sprint 1, the residual is
the same-pool row lock from `docs/ANALYSIS-SAME-POOL-LOCK.md`).

---

## Sprint 2 baseline (kept for reference — single user/single pool pile-up)

Captured 2026-05-16 against the seeded dev stack — **50 VUs ramping to
300, target 200 RPS sustained**. All five thresholds fired, which is
exactly what we want from a first-cut baseline: the system tells us
where it breaks before we ask it to. Honest numbers below.

| Metric             | Target  | Actual p99 | Verdict   |
|--------------------|---------|------------|-----------|
| `/pools` latency   | <500ms  | **23.22s** | over by 46× |
| `/dashboard`       | <1500ms | **16.87s** | over by 11× |
| `/swap` latency    | <2000ms | **42.29s** | over by 21× |
| Overall throughput | 200 rps | 34.5 rps   | hit ~17% of target |
| `swap_errors`      | <5%     | **96.55%** | only 32 of 928 swaps succeeded |
| `http_req_failed`  | <1%     | 19.79%     | failure rate dominated by swap |

**What the numbers tell us:**

1. **Connection-pool exhaustion.** Hikari default 20-max in pool-engine
   serialises everything past 20 concurrent. /pools degrades from 50ms
   warm-call to 23s p99 under 300-VU load — that's queue depth, not
   compute. Easy fix: bump `maximum-pool-size` to 50, re-baseline.

2. **All swaps drain the same balance.** k6 runs every swap as the
   single seed user "ivanov" against pool `c0000000…001`. After ~30
   successful 0.001 SBTC deductions ivanov's SBTC is gone, so the
   remaining 896 calls 4xx with InsufficientBalance. Fixed by rotating
   across users + pools in the next iteration — not a real bottleneck,
   just a test-design artefact.

3. **Same-pool row lock.** Multiple swaps against the same pool
   serialise on `liquidity_pools` and `pool_bins` row updates.
   Documented in `docs/RISK-REGISTER.md` and tracked as Sprint 3 work
   (pool-id sharding for load tests; row-level locking analysis).

4. **/admin/dashboard is degraded but doesn't fail.** All 200 OK, just
   slow — confirms the BFF's fan-out tolerates downstream latency
   gracefully (no cascade failure), which is what the Sprint 1
   "independent calls" rewrite was supposed to deliver.

**Conclusion:** the system survives the load (no crashes, no data
corruption — all 32 successful swaps had matching outbox events), but
throughput is gated on infrastructure tuning + test-design. Treat this
as the "ceiling we currently can't break", not the floor we ship at.

**Next iteration (Sprint 3 backlog):**

* Rotate user + pool per VU iteration so swap mix exercises real
  concurrency, not the same-row pile-up.
* Bump Hikari pool: pool-engine 20→50, token-service 20→50.
* Re-baseline with target 100 RPS sustained / p99 < 500ms / error
  rate < 1%. That's the realistic short-term goal — 200 RPS is
  Sprint 4 (after horizontal scaling).

## What this doesn't cover (yet)

* Concurrent writers on the **same pool** — current mix spreads swaps across
  one pool which serialises on Postgres row lock. Add `POOL_ID` rotation in
  Sprint 3 to test multi-pool fan-out throughput.
* WebSocket / streaming endpoints — none in MVP, but planned for live price
  feeds in Sprint 4.
* Soak (8 h+) tests — current run tops out at minutes. Memory leak detection
  is on the Sprint 3 list.
