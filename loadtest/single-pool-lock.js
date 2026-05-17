// Sprint 4 #4.7 — single-pool concurrency test
//
// Purpose: prove the optimistic-locking fix scales linearly under
// same-pool contention. Pre-fix: N concurrent swaps serialised on
// row-level lock, latency N×tx_duration. Post-fix: contention shows
// as retries in logs, but throughput stays near-linear.
//
// All VUs hit the SAME pool (SBER/SRUB), rotating only the user so
// deduct/credit doesn't pile up on one balance row. Compare latency
// + retry rate to the multi-pool baseline.js.
//
// Run:
//   k6 run -e VUS=50 -e DURATION=60s loadtest/single-pool-lock.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const POOL_ID  = 'c0000000-0000-0000-0000-000000000101';            // SBER/SRUB (high TVL)
const TOKEN_IN = 'b0000000-0000-0000-0000-000000000101';            // SBER
const SWAP_USERS = ['ivanov@example.com', 'sidorov@example.com', 'admin@sber-dlmm.ru'];

const swapLatency = new Trend('swap_latency', true);
const swapErrors  = new Rate('swap_errors');
const swap5xx     = new Rate('swap_5xx');

export const options = {
  scenarios: {
    contention: {
      executor: 'ramping-arrival-rate',
      startRate: 5,
      timeUnit: '1s',
      preAllocatedVUs: parseInt(__ENV.VUS || '50'),
      maxVUs: 150,
      stages: [
        { duration: '15s', target: 30 },
        { duration: __ENV.DURATION || '60s', target: 80 },     // peak: 80 swaps/sec on one pool
        { duration: '10s', target: 0 },
      ],
    },
  },
  thresholds: {
    'swap_latency': ['p(99)<2000'],         // 2s ceiling under heavy contention
    'swap_5xx':     ['rate<0.10'],          // 10% 5xx tolerated (incl. lock-fail-after-retries)
  },
};

function loginOnce(email, password) {
  const r = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({ email, password }), {
    headers: { 'Content-Type': 'application/json' },
  });
  if (r.status !== 200) throw new Error(`Login failed: ${email}`);
  return r.json('accessToken');
}

export function setup() {
  return { tokens: SWAP_USERS.map(e => loginOnce(e, 'Demo1234')) };
}

export default function (data) {
  const tok = data.tokens[(__VU + __ITER) % data.tokens.length];
  const idKey = `single-pool-${__VU}-${__ITER}-${Date.now()}`;
  const body = JSON.stringify({
    poolId: POOL_ID,
    tokenInId: TOKEN_IN,
    amountIn: 100,                                                  // tiny — avoid draining the pool
    minAmountOut: 0,
    idempotencyKey: idKey,
  });
  const res = http.post(`${BASE_URL}/api/v1/pools/swap`, body, {
    headers: { Authorization: `Bearer ${tok}`, 'Content-Type': 'application/json' },
    tags: { name: 'swap' },
  });
  swapLatency.add(res.timings.duration);
  swapErrors.add(res.status !== 200);
  swap5xx.add(res.status >= 500);
  check(res, { 'swap 200': r => r.status === 200 });
  sleep(0.02);
}
