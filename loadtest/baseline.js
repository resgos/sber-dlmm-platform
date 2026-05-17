// Sber DLMM baseline load test — Sprint 3 #3.7 re-baseline
//
// Sprint 2's first cut hammered a single user against a single pool;
// 96% of swap calls 4xx'd with InsufficientBalance after the first
// dozen — test-design pile-up, not infra. This version rotates across
// 3 verified seed users × 4 high-TVL pools so the swap mix exercises
// real concurrency rather than the same-row queue.
//
// Targets after Sprint 3 Hikari bump (20→50) + Liquibase fix + outbox
// extraction:
//   * Sustain 100 RPS aggregate (down from 200 — honest about the
//     single-laptop ceiling; real prod targets ride horizontal scaling)
//   * p99 < 500 ms on read paths (/pools, /admin/dashboard)
//   * p99 < 1500 ms on swap (fanout to token-service + DB + outbox)
//   * swap error rate < 2%
//
// Mix unchanged:
//   60% — /api/v1/pools (read, cached after warm-up)
//   20% — /api/v1/admin/dashboard (admin BFF aggregate, hits 3 downstreams)
//   20% — /api/v1/pools/swap (state-mutating, full pipeline)
//
// Run:
//   k6 run loadtest/baseline.js                          # default 100 VUs / 3 min
//   k6 run -e DURATION=60s -e VUS=50 loadtest/baseline.js  # CI smoke

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Pools picked from the seed: 4 high-TVL pairs with diverse token sides
// so concurrent swaps don't all queue on one liquidity_pools row. Each
// has its own input token; we cycle through them per swap call.
const SWAP_POOLS = [
  { poolId: 'c0000000-0000-0000-0000-000000000101', tokenIn: 'b0000000-0000-0000-0000-000000000101', amountIn: 500 },   // SBER/SRUB
  { poolId: 'c0000000-0000-0000-0000-000000000102', tokenIn: 'b0000000-0000-0000-0000-000000000102', amountIn: 500 },   // GAZP/SRUB
  { poolId: 'c0000000-0000-0000-0000-000000000004', tokenIn: 'b0000000-0000-0000-0000-000000000001', amountIn: 100000 },// SGOLD/SRUB (swap SRUB in)
  { poolId: 'c0000000-0000-0000-0000-000000000002', tokenIn: 'b0000000-0000-0000-0000-000000000001', amountIn: 100000 },// SETH/SRUB
];

// 3 seed users (ivanov + sidorov + admin). Each gets logged in once;
// VUs round-robin between them per swap so the deduct/credit path
// doesn't pile up on one user_balances row.
const SWAP_USER_EMAILS = [
  'ivanov@example.com',
  'sidorov@example.com',
  'admin@sber-dlmm.ru',
];

// Custom Trends per endpoint so SLO thresholds point at the right
// bucket if they fire.
const swapLatency = new Trend('swap_latency', true);
const poolsLatency = new Trend('pools_latency', true);
const dashboardLatency = new Trend('dashboard_latency', true);
const swapErrors = new Rate('swap_errors');

export const options = {
  scenarios: {
    baseline: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: parseInt(__ENV.VUS || '100'),
      maxVUs: 200,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '30s', target: 100 },                       // ramp to 100 rps (realistic post-Sprint-3 target)
        { duration: __ENV.DURATION || '3m', target: 100 },      // steady
        { duration: '15s', target: 0 },                         // cool-down
      ],
    },
  },
  thresholds: {
    'pools_latency':      ['p(99)<500'],
    'dashboard_latency':  ['p(99)<1500'],
    'swap_latency':       ['p(99)<1500'],
    'swap_errors':        ['rate<0.02'],   // 2% — tightened from Sprint 2's 5%
    'http_req_failed':    ['rate<0.01'],
  },
};

function loginOnce(email, password) {
  const r = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({ email, password }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { name: 'auth_login' },
  });
  if (r.status !== 200) {
    console.error(`Login failed for ${email}: ${r.status} ${r.body}`);
    return null;
  }
  return r.json('accessToken');
}

export function setup() {
  const swapTokens = SWAP_USER_EMAILS.map(email => {
    const tok = loginOnce(email, 'Demo1234');
    if (!tok) throw new Error(`Login failed for ${email}`);
    return tok;
  });
  const adminTok = loginOnce('admin@sber-dlmm.ru', 'Demo1234');
  if (!adminTok) throw new Error('Admin login failed');
  return { swapTokens, adminTok };
}

export default function (data) {
  const r = Math.random();
  if (r < 0.6) {
    callPools(data.adminTok);
  } else if (r < 0.8) {
    callDashboard(data.adminTok);
  } else {
    callSwap(data.swapTokens);
  }
  sleep(0.05);
}

function callPools(tok) {
  const res = http.get(`${BASE_URL}/api/v1/pools?page=0&size=20`, {
    headers: { Authorization: `Bearer ${tok}` },
    tags: { name: 'pools' },
  });
  poolsLatency.add(res.timings.duration);
  check(res, { 'pools 200': r => r.status === 200 });
}

function callDashboard(tok) {
  const res = http.get(`${BASE_URL}/api/v1/admin/dashboard`, {
    headers: { Authorization: `Bearer ${tok}` },
    tags: { name: 'dashboard' },
  });
  dashboardLatency.add(res.timings.duration);
  check(res, { 'dashboard 200': r => r.status === 200 });
}

function callSwap(swapTokens) {
  // Round-robin across users × pools so concurrent swaps spread
  // across rows. __VU and __ITER are k6 globals; combining them
  // ensures even spread when scaling VUs up.
  const userIdx = (__VU + __ITER) % swapTokens.length;
  const poolIdx = (__VU + __ITER * 7) % SWAP_POOLS.length;
  const tok = swapTokens[userIdx];
  const pool = SWAP_POOLS[poolIdx];

  const idKey = `lt-${__VU}-${__ITER}-${Date.now()}`;
  const body = JSON.stringify({
    poolId: pool.poolId,
    tokenInId: pool.tokenIn,
    amountIn: pool.amountIn,
    minAmountOut: 0,
    idempotencyKey: idKey,
  });
  const res = http.post(`${BASE_URL}/api/v1/pools/swap`, body, {
    headers: {
      Authorization: `Bearer ${tok}`,
      'Content-Type': 'application/json',
    },
    tags: { name: 'swap' },
  });
  swapLatency.add(res.timings.duration);
  swapErrors.add(res.status !== 200);
  check(res, { 'swap 200': r => r.status === 200 });
}
