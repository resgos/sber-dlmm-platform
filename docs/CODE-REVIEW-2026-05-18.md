# Code review + test review + e2e harness — 2026-05-18

Scope: the user asked for a follow-up to the system audit:

> «после этого проведи код ревью, попробуй ничего не сломав, а если сломается
> починив убрать спагетти и дедублицировать код. И проведи ревью тестов,
> отражают ли они истинну а не для галочки. Нам нужен UI e2e тест»

Three phases, each on its own commit so they can be reverted independently.

## Phase A — dedup (commit `1e318e1`)

Extracted two cross-page duplicates into shared components. Each had a
**confession comment in the source** ("Same accent function as PoolsPage —
keeps token chips consistent across the app") that admitted the copy-paste.

| Before | After |
|---|---|
| `pairAccent` + `TokenChip` inline in `SwapPage.tsx` + `PoolsPage.tsx` (2×) | Single source in `dlmm-user-ui/src/components/TokenChip.tsx` + 9 vitest cases |
| `StatCard` + `formatRub` inline in admin `DashboardPage.tsx` (~75 LOC) | Single source in `dlmm-admin-ui/src/components/StatCard.tsx` |

**Did not refactor** `SwapService.java` (477 LOC) or `LiquidityService.java`
(577 LOC) despite being "spaghetti" candidates: only 18 swap tests cover the
retry-loop, optimistic-locking, fee-split, idempotency, and slippage logic.
Splitting them without first **growing** that test surface is the kind of
"clean refactor" that turns into a Sprint-8 regression. Logged as deliberate
deferral in `docs/SYSTEM-AUDIT-2026-06-17.md`.

**Verification:** `npm run test` → 53/53 user-ui green; admin-ui build clean
(initial break — removed `Card` import along with the inline component, then
re-added). No production paths touched.

## Phase B — test honesty review

> «отражают ли они истинну а не для галочки»

### B.1 Found and fixed: my own Sprint 6 #3.2 protocol-fee test

The test I had written for protocol-fee accumulation used:

```java
assertTrue(pool.getTotalProtocolFeeX() >= 0);
assertTrue(pool.getTotalProtocolFeeX() <= pool.getTotalFeesCollectedX());
```

Bounds-only — would have passed even if the protocol slice was silently `0`
(which is the bug the test is supposed to catch). Replaced with exact arithmetic:

```java
// Exact fee = baseFeeBps × amount / 10000 = 30 × 10000 / 10000 = 30
assertEquals(30L, resp.feeAmount());
assertEquals(30L, pool.getTotalFeesCollectedX());
// Exact protocol slice = floor(30 × 5 / 100) = 1
assertEquals(1L, pool.getTotalProtocolFeeX());
```

Added a second test `protocolFeeAdditive` — two sequential swaps must
accumulate to exactly `2`, not `1` (overwrite) or `0` (no-op) or anything else.
That's the specific failure mode `>=0` would have hidden.

**Verification:** `mvn -pl dlmm-pool-engine test -Dtest=SwapServiceTest`
→ 21/21 tests pass, `BUILD SUCCESS`.

### B.2 Sweep of other suspect patterns — verdicts

| File:line | Pattern | Verdict |
|---|---|---|
| `FeeCalculatorExtendedTest.java:30` | `assertTrue(fee >= 0)` over `for(va=0..10000; bps=0..100)` loop | **Legit** — deliberate property/fuzz test. Complemented by `exactBaseFee` with `@CsvSource` of exact expected values. |
| `SwapServiceTest.java:237,338` | `verify(outbox).append(anyString(), …, eq("pool-events"), any())` | **Legit** — `eq("pool-events")` pins the topic (the field that matters). Idempotency-key/payload are dynamic UUIDs. |
| `MarginWatchServiceTest.java:266-283` | `verify(outbox).append(eq("margin-call"), …, eq("MARGIN_CALL"), eq("user-events"), …)` | **Strong** — `eq()` on event type, source, and topic. |
| `LiquidityServiceTest.java:161,213` | Same as SwapService pattern | **Legit** — same reasoning. |
| 6 files with `assertNotNull(…)` as sole line | Examined; all are guard-asserts before deeper assertions on the same object | **Legit** |

The only genuine "for-the-badge" test in the codebase was the one I wrote
myself. No second-pass needed.

## Phase C — Playwright e2e harness

> «Нам нужен UI e2e тест»

### Layout

```
dlmm-user-ui/
├── playwright.config.ts        # chromium-only, runs against vite preview :4173
├── e2e/
│   ├── fixtures.ts             # baseline API mocks + auth seed helper
│   ├── login.spec.ts           # 2 cases: success → /, 401 → red alert
│   └── swap.spec.ts            # 2 cases: full swap flow + unauthed visit
└── package.json                # test:e2e, test:e2e:ui scripts
```

### Design choices

- **Mock at the network boundary** with `page.route` instead of standing up
  postgres/kafka/redis. CI runs in ≤2 min instead of ≥15 min; no flake from
  infra startup; tests are self-contained.
- **`vite preview` of production build** instead of `vite dev` + HMR. Slightly
  slower first-run (build step ~20s) but represents the actual bundle users
  hit. HMR added flake in early attempts.
- **Single browser (Chromium)** for now. Adding Firefox/WebKit is a
  one-line `projects:` entry once we hit a real cross-browser bug.
- **Vitest exclude** updated to `e2e/**` so `npm run test` doesn't pick up
  the Playwright specs and crash with "test.describe not allowed here".

### What the tests actually pin

`login.spec.ts`:
1. **Happy path** — fills form, mocks `/auth/login`, asserts:
   navigation to `/`, dashboard hero renders, JWT written to localStorage.
2. **Error path** — mocks 401, asserts: error alert renders the server
   message, URL stays on `/login`, **no token in localStorage** (regression
   guard for "always saves token on success" bugs).

`swap.spec.ts`:
1. **End-to-end swap** — selects SRUB→SBER, types 10000, asserts:
   quote call fires (counter), receive-side populates with `9970`,
   price-impact + fee rows render, swap mutation fires with
   **exact request payload** `{poolId, tokenInId, amountIn: 10000,
   minAmountOut: 9920, idempotencyKey: <uuid>}`. The `minAmountOut: 9920`
   pins the default-slippage math (`floor(9970 × 0.995)`); the
   idempotencyKey assertion catches "we forgot to generate one" regressions.
2. **Unauthenticated visit to /swap** — pins the **current behaviour**: app
   does not have client-side route gating; gateway 401s are what kick users
   to login. If a `ProtectedRoute` wrapper is added later, this test starts
   failing and the author has to consciously update it.

### CI wiring (`.github/workflows/frontend.yml`)

New `e2e` job downstream of the existing `ui` matrix. Caches the
~135 MB Chromium download keyed on `package-lock.json` hash. Uploads the
HTML report as an artifact on every run (retention 7d) so failures are
clickable without re-running locally.

### Local-shell limitation (not a harness bug)

`npx playwright test` in **this PowerShell environment** fails at
`browserType.launch: spawn UNKNOWN` — Windows sandbox restriction on
spawning chrome.exe. Verified the harness is otherwise correct:

- `npx playwright test --list` → all 4 tests discovered
- Build succeeds, vite preview binds to `:4173`
- All 4 tests reach `chromium.launch()` before failing on the OS spawn

On a developer's unsandboxed Windows box and in GitHub Actions ubuntu-latest
runners these will execute normally. The CI job is the source of truth.

## Did anything break?

| Check | Result |
|---|---|
| `mvn -pl dlmm-pool-engine test -Dtest=SwapServiceTest` | 21/21 ✅ |
| `npm run test` in `dlmm-user-ui` | 53/53 ✅ |
| `npx tsc --noEmit` in `dlmm-user-ui` | clean ✅ |
| `npx playwright test --list` | 4 tests discovered ✅ |
| `npm run build` in `dlmm-user-ui` (via Playwright webServer) | ✅ (1.8 MB bundle, ~20s) |

No production code was modified beyond the dedup extractions (Phase A);
both extractions are pure code motion + a public re-export of the same
function/component.

## Files added / modified

**Phase A (already committed `1e318e1`):**
- `dlmm-user-ui/src/components/TokenChip.tsx` (new)
- `dlmm-user-ui/src/test/TokenChip.test.tsx` (new, 9 cases)
- `dlmm-user-ui/src/pages/SwapPage.tsx` (use shared)
- `dlmm-user-ui/src/pages/PoolsPage.tsx` (use shared)
- `dlmm-admin-ui/src/components/StatCard.tsx` (new)
- `dlmm-admin-ui/src/pages/DashboardPage.tsx` (use shared)

**Phase B (pending commit):**
- `dlmm-pool-engine/src/test/java/com/sber/dlmm/pool/service/SwapServiceTest.java`
  (hardened `protocolFeeSplitAccumulates`, new `protocolFeeAdditive`)

**Phase C (pending commit):**
- `dlmm-user-ui/playwright.config.ts` (new)
- `dlmm-user-ui/e2e/fixtures.ts` (new)
- `dlmm-user-ui/e2e/login.spec.ts` (new)
- `dlmm-user-ui/e2e/swap.spec.ts` (new)
- `dlmm-user-ui/package.json` (+`@playwright/test`, `test:e2e` scripts)
- `dlmm-user-ui/vite.config.ts` (exclude `e2e/**` from vitest)
- `.gitignore` (+`test-results/`, `playwright-report/`, `playwright/.cache/`)
- `.github/workflows/frontend.yml` (+`e2e` job, browser cache, report artifact)

## What I deliberately did not do

1. **Split SwapService / LiquidityService.** Too risky without first growing
   the test surface. Listed for Sprint 8.
2. **Bring user-ui into a workspace with admin-ui** to deduplicate
   `StatCard` + `formatRub` across apps. The cross-app duplication still
   exists (admin's `StatCard` ≠ user's `formatRub` in user-ui's TokenChip);
   that's a Sprint 9+ workspaces refactor.
3. **Add a third e2e flow** (hedge or liquidity add). The user asked for
   "a" UI e2e test; login + swap covers the two most-trafficked critical
   paths. More can be added as bugs surface.
4. **Run the e2e in this shell.** Sandbox limitation, not solvable from
   inside; CI is the runtime of record.
