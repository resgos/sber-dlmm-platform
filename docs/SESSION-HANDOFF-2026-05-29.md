# SESSION HANDOFF — 2026-05-29

**Read this first in a new session.** Exhaustive state of the Sber DLMM project
after the post-demo feature round. Complements `CLAUDE.md` (architecture/sprints)
— this file is the *current* operational truth + gotchas learned the hard way.

---

## 0. Quick start (60-second orientation)

- **Repo:** `github.com/resgos/sber-dlmm-platform`
- **Active branch:** `claude/elated-elgamal-dba521` (HEAD `262894b`, pushed, in sync with origin)
- **Worktree:** `C:\Users\rusgr\Downloads\sber-dlmm-platform-git\.claude\worktrees\elated-elgamal-dba521`
  (NB: there is ALSO the main clone at `...\sber-dlmm-platform-git\` — a few one-off scripts
  got written there by accident; the worktree is the source of truth, work there.)
- **Stack:** `cd docker && docker compose up -d` → 19 containers. Gateway `:8080`,
  user-ui `:3001`, admin-ui `:3000`, services `:8081–:8088`, postgres/redis/kafka, grafana/prometheus/clickhouse.
- **Demo creds:** user `ivanov@example.com` / `Demo1234`; admin `admin@sber-dlmm.ru` / `Demo1234`.
- **Model preference:** user wants **Opus** ("не не, какой соннет"). Autonomy: "ничего не спрашивай просто делай".
- **Language:** user communicates in Russian; mirror it.

---

## 1. Current live state (verified 2026-05-29 ~16:40)

| | |
|---|---|
| Containers | 19/19 up; gateway + admin-bff `UP` |
| user-ui served | `index-BejrT0_o.js` (PC-02 + OB-01 + SM-01 + SK-01 + T-01) |
| admin-ui served | `index-D7y4RPGr.js` (DS-02 dashboard + health/TVL fixes) |
| pool-engine | rebuilt image (19:18) — F-09 admin fee fields live |
| OHLCV candles | 1650 across 22 pools (chart populated) |
| Tests | sberkot 7/7 · user-ui 232 · admin-ui 24 — green |
| Working tree | clean, pushed |

---

## 2. What this session shipped (all on `claude/elated-elgamal-dba521`, pushed)

12 commits, `e8ccafd..262894b`. Feature batch + data fixes + admin redesign:

- **T-01** — `dlmm-user-ui/src/sber-theme.css`: spacing scale `--space-1..8`,
  data-viz palette `--viz-up/down/external` (+`-soft`, dark variants), dark
  `--brand-primary-soft`. Fixed SPOT/CURVE/BID strategy-card dark-theme contrast
  (was hardcoded `#F0FFF4`), `StatCard` hardcoded colors. RU strategy labels via
  `dlmm-user-ui/src/lib/strategy.ts`.
- **PC-02** — real INTERNAL price chart. `dlmm-user-ui/src/components/PoolPriceChart.tsx`
  (lightweight-charts v4: candles/line/timeframe/volume) + `lib/ohlcv.ts` (1m→5m/1h/1d
  roll-up) + `lib/vizTheme.ts` + on Swap page. Consumes `/api/v1/oracle/ohlcv/{poolId}`.
- **OB-01** — order book «стакан» from OUR bins. `dlmm-user-ui/src/components/OrderBook.tsx`
  (asks=reserveX above active, bids=reserveY below; mid+spread; recent trades from our txns).
  Mounted on PoolDetailPage; `PoolActionTabs`/`PoolSwapPanel` made controllable for row-click→price.
- **SM-01** — Simple⇄Pro. `dlmm-user-ui/src/store/uiPrefStore.ts` (mode:'simple'|'pro',
  legacy migration), `pages/SimpleTradePage.tsx` (Купить/Продать market + basic
  add-liquidity SPOT ±10 bins), `UserLayout.tsx` header pill + nav gating, `/simple` route.
- **SK-01** — Сберкот assistant. `dlmm-user-ui/src/components/sberkot/`
  (`SberkotMascot.tsx` SVG 4 poses, `SberkotAssistant.tsx` widget, `hints.ts` pure
  route→hint module) + `src/test/sberkotHints.test.ts` (7 tests) + CSS in sber-theme.css.
  Mounted in UserLayout.
- **DS-02** — admin dashboard redesign per Claude Design mockup
  (`docs/design/admin-dashboard-claude-design/project/Dashboard.html`). New
  `dlmm-admin-ui/src/components/dashboard/*` (KPI tiles, Sparkline, DeltaPill,
  TvlAreaChart, ServiceHealthStrip/Card, useServiceHealth) + `lib/dashboardSeries.ts`.
  Rebuilt `pages/DashboardPage.tsx`. Tokens: #21A038-accent/#FAFAF8/#ECEAE3/14px/Onest.
- **F-09/F-10/F-11** data fixes (see ledger). **OHLCV backfill** `docker/09-seed-ohlcv-backfill.sql`.
  Admin-dash fixes: nginx `/actuator` proxy (`dlmm-admin-ui/nginx.conf`) + `useServiceHealth`
  honesty + `poolTvlRub` coherence.

---

## 3. Findings ledger

| ID | Issue | Status |
|----|-------|--------|
| F-09 | fee→₽ rollups = 0 (user KPIs + admin «Собрано комиссий») | ✅ fixed both sides (admin shows 4.58 млрд) |
| F-10 | admin Users «Последний вход» "—" | ✅ fixed (`UserProfileResponse.lastLoginAt`) |
| F-11 | admin Pools stat cards summed only page | ✅ fixed (aggregate query) |
| F-01 | pools APY/Volume 0 | ✅ holding (re-run 05-seed) |
| F-02 | chart "freeze" | ✅ CDP-screenshot artifact only, not real |
| F-07 / price-impact | "странный" | ✅ `computePriceImpact` (0% within-bin) |
| chart +9190% badge | OHLCV outlier | ✅ base-price fallback in 09-seed |
| admin «0 из 8 в норме» | /actuator not proxied | ✅ nginx proxy + honest fetchHealth |
| admin SBTC TVL «1500 квадриллион» | poolTvlRub price-multiply | ✅ → totalTvlX+totalTvlY (2.13 трлн) |
| **F-12** | **remove-liquidity over-returns quote token (+49%)** | 🟠 **OPEN** — see §5 |

Detailed docs: `docs/UI-RESEARCH-2026-05-29.md`, `docs/CONSISTENCY-CHECK-2026-05-29.md`,
`docs/UI-TEST-2026-05-29.md`, `docs/UX-FINDINGS-2026-05-26.md`.

---

## 4. Open backlog (tracked, not regressions)

- **LO-02** — limit-order UI (form + open orders + cancel). FE-ready against `mockApi`;
  needs LO-01 backend to go live.
- **MB-01** — mobile UI (drawer nav, responsive grids/tables/charts, bottom tab-bar, ≥44px targets).
- **DS-01** — design-polish sweep (apply `--space-*` rhythm, 3-tier elevation, hero
  hierarchy, ~75 remaining inline hardcoded hex across ~14 files → CSS vars; both-theme
  contrast). Includes **hex-baseline rebaseline** (`node scripts/check-no-hex-in-tsx.mjs --update`
  — SberkotMascot's intentional brand-hex + 3 pre-existing files: DemoBranding/PricingPage/admin PilotsHealthPage).
- **PC-01** [BE, gated] — external market sync (MOEX/FX/crypto → price-oracle) + offline mock adapter.
- **LO-01** [BE, gated] — limit-order engine (place/cancel/list + price-cross fill + escrow).
- Plan docs: `docs/FEATURE-BATCH-PLAN-2026-05-29.md`, `docs/DESIGN-DIRECTION-2026-05-29.md` (north-star tokens + per-surface specs).

---

## 5. F-12 — the one open correctness bug (read before touching liquidity)

**Symptom:** isolated add→remove (no swaps between) returns ~49% MORE quote-token
than deposited. **Root cause:** 13/22 active bins violate `reserveX·price + reserveY = liquidity`
(compositionFactor > 1; SBTC 998×). Seed's 210 history swaps were SQL inserts that
set `pool_bins` reserves without maintaining `liquidity`. `SwapService` itself
conserves L correctly (never sets liquidity). On remove,
`amountY = reserveY · shares / liquidity` over-pays because `reserveY/liquidity > 1`.
Pool conservation holds (reserve drop = user gain + exit fee) → not money-from-nothing,
but an unfair split at other LPs' expense.
**Fix (needs its own test cycle, NOT a hot patch):** seed-reconciliation that recomputes
`liquidity = round(reserveX·price + reserveY)` per bin AND scales `position_bins.shares`
proportionally; add a bin-invariant test (`reserveY ≤ liquidity` ∀ bins).
**Mitigation now:** avoid live add+remove on SBTC in demos. Swap/add/claim are exact.
Verify with `scripts/consistency-check.sh`.

---

## 6. ⚠️ OPERATIONAL GOTCHAS (these cost hours this session — read them)

### Docker / deploy
- **Frontend deploy = `bash scripts/redeploy-frontends.sh`** (host `npm run build` +
  `docker cp` dist into running nginx). Does NOT need the registry. **Re-run after every
  `docker compose up`** (the cp is lost on container recreate). Then hard-refresh (Ctrl+Shift+R).
- **`docker compose build` is FLAKY for backend:** can exit 0 but NOT update the image
  (BuildKit tag race) → always check `docker images ... CreatedAt`. Use `--no-cache` to be sure.
- **Registry DNS can drop** (`lookup registry-1.docker.io: no such host`) → then ALL builds
  fail (even cached — BuildKit HEAD-checks base-image manifests). Happened mid-session; recovered.
  When network's back, a plain `docker compose build <svc>` works and updates the image.
- **Docker Desktop WSL2 engine can die** (daemon pipe gone, `vmmem`/`vmmemWSL` not running,
  `docker ps` hangs). Fix: relaunch `Docker Desktop.exe`; the stack self-restores because every
  service has `restart: unless-stopped`. Check engine via PowerShell `Test-Path '\\.\pipe\dockerDesktopLinuxEngine'`.
- **nginx.conf changes are NOT deployed by redeploy-frontends** (only dist). Apply live via
  `docker cp nginx.conf <c>:/etc/nginx/conf.d/default.conf && docker exec <c> nginx -s reload`.
  (The `/actuator` proxy on admin-ui was added this way + committed to the repo for next rebuild.)

### Seed scripts (`docker/`) — idempotency matters
- `05-seed-volume-refresh.sql` — **SAFE to re-run; MUST re-run within ~24h of any demo**
  (pool-engine scheduler ages swaps out of the 24h window → volume/APY drift to 0).
- `06-seed-tvl-rescale.sql`, `08-seed-balance-rescale.sql` — **NON-idempotent** (divide by a
  constant). NEVER re-run — shrinks values 10000× again.
- `07-seed-fix-backend-bugs.sql` — insert-if-missing, safe.
- `09-seed-ohlcv-backfill.sql` — **idempotent** (ON CONFLICT DO NOTHING). Backfills chart
  candles from transactions. Re-run after a DB reset. Run order after `down -v`: 05,06,07,08,09.

### Browser automation
- **recharts pages time out CDP `captureScreenshot`** (ResizeObserver continuous repaint) —
  NOT a real freeze. Use `get_page_text` + `javascript_tool` (action `javascript_exec`, no
  top-level `return`) to verify. **lightweight-charts (PC-02) is canvas → screenshots fine.**
- Tokens expire → apiClient redirects to /login (correct). Re-login with demo creds.

### Multi-agent batch pattern (worked well)
- Spawn `Agent` with `isolation: 'worktree'` + `run_in_background`. Agents build+commit in
  their own worktree. **Their worktree starts on a STALE base** — instruct them to branch off
  `claude/elated-elgamal-dba521`. Push from agents often fails (no network) — that's fine:
  the branch ref is in the shared `.git`, so the coordinator merges it locally
  (`git merge --no-ff claude/<unit>-2026-05-29`). Only conflict seen: `sber-theme.css`
  (two agents appending) — keep both blocks, mind brace balance.

### Known repo notes (from CLAUDE.md, still relevant)
- JWT/outbox/Resilience4j shared in `dlmm-common`. Liquibase preConditions are a tactical hack.
- `docker/.env` ships dev secrets. No CI gate enforced on merge yet (lint ratchets red — see DS-01).

---

## 7. How to verify a change end-to-end
1. `bash scripts/redeploy-frontends.sh` (FE) and/or rebuild the relevant backend svc.
2. Smoke: `docs/PRE-DEMO-CHECKLIST.md` (STEP 0 redeploy, STEP 0b seed, auth/health/feature curls).
3. Money-conservation: `bash scripts/consistency-check.sh` (swap/add/claim exact; remove flags F-12).
4. UI: log in (demo creds), walk the surface; for recharts use get_page_text/js_tool.

---

## 8. Suggested next move
Pick from §4. Highest-value: **DS-01** (polish + hex-baseline so CI lint goes green) or
**F-12** (the correctness bug) or **MB-01** (mobile). LO-01+LO-02 together deliver limit orders.
All FE work deploys via redeploy-frontends; BE needs a working registry/network.
