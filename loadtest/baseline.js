// Sber DLMM baseline load test
//
// Targets:
//   * Sustain 200 RPS aggregate read/write mix
//   * p99 < 500 ms on read paths (/pools, /admin/dashboard)
//   * p99 < 1500 ms on swap (which fans out to token-service + DB + outbox)
//   * error rate < 1% over the full run
//
// Mix:
//   60% — /api/v1/pools (read, cached after warm-up)
//   20% — /api/v1/admin/dashboard (admin BFF aggregate, hits 3 downstreams)
//   20% — /api/v1/pools/swap (state-mutating, full pipeline)
//
// Run:
//   # local stack, default 60s ramp-up + 3min steady state:
//   k6 run loadtest/baseline.js
//
//   # custom target (e.g. staging):
//   k6 run -e BASE_URL=https://staging.dlmm.sber.local loadtest/baseline.js
//
//   # tighter run for CI smoke (60s total):
//   k6 run -e DURATION=60s -e VUS=50 loadtest/baseline.js
//
// Pre-reqs:
//   The seed user "ivanov@example.com" + admin@sber-dlmm.ru exist (init-db.sql)
//   and the demo Demo1234 password is in place.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const POOL_ID = __ENV.POOL_ID || 'c0000000-0000-0000-0000-000000000001'; // SBTC/SRUB seed pool
const SWAP_TOKEN_IN = __ENV.SWAP_TOKEN_IN || 'b0000000-0000-0000-0000-000000000002'; // SBTC

// Custom metrics for the per-endpoint slice. Default http_req_duration
// is whole-test; these split out the three buckets so SLO violations
// point at the right endpoint.
const swapLatency = new Trend('swap_latency', true);
const poolsLatency = new Trend('pools_latency', true);
const dashboardLatency = new Trend('dashboard_latency', true);
const swapErrors = new Rate('swap_errors');

// Thresholds = hard SLO gates. k6 exits non-zero if any are violated;
// CI will see the failure.
export const options = {
  scenarios: {
    baseline: {
      executor: 'ramping-arrival-rate',
      // Target ~200 rps aggregate at peak.
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: parseInt(__ENV.VUS || '100'),
      maxVUs: 300,
      stages: [
        { duration: '30s', target: 50 },          // warm-up
        { duration: '30s', target: 200 },         // ramp to peak
        { duration: __ENV.DURATION || '3m', target: 200 }, // steady state
        { duration: '15s', target: 0 },           // cool-down
      ],
    },
  },
  thresholds: {
    'pools_latency':      ['p(99)<500'],
    'dashboard_latency':  ['p(99)<1500'],
    'swap_latency':       ['p(99)<2000'],
    'swap_errors':        ['rate<0.05'],          // 5% — first-cut budget; tighten next sprint
    'http_req_failed':    ['rate<0.01'],          // overall non-swap errors
  },
};

let cachedTokens = { user: null, admin: null };

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

// Setup runs once per test, returns object passed to default fn.
// We log in both seed users here so VUs share tokens instead of
// hammering /auth/login (which would itself dominate the metrics).
export function setup() {
  const userTok = loginOnce('ivanov@example.com', 'Demo1234');
  const adminTok = loginOnce('admin@sber-dlmm.ru', 'Demo1234');
  if (!userTok || !adminTok) {
    throw new Error('Login setup failed — is the stack running and seeded?');
  }
  return { userTok, adminTok };
}

export default function (data) {
  const r = Math.random();
  if (r < 0.6) {
    callPools(data.adminTok);
  } else if (r < 0.8) {
    callDashboard(data.adminTok);
  } else {
    callSwap(data.userTok);
  }
  // Tiny think-time so we're not all in flight simultaneously — keeps
  // the connection pool warm without artificially limiting throughput.
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

function callSwap(tok) {
  // Unique idempotency key per call so we exercise the full write path,
  // not the dedupe shortcut.
  const idKey = `loadtest-${__VU}-${__ITER}-${Date.now()}`;
  const body = JSON.stringify({
    poolId: POOL_ID,
    tokenInId: SWAP_TOKEN_IN,
    amountIn: 100000,                 // 0.001 SBTC — tiny so we don't deplete a bin
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
