# Feature Batch Plan — 2026-05-29 (trading-UX + Sberkot)

**Branch:** `claude/elated-elgamal-dba521`
**Goal:** ship the next wave of user-facing trading features + a design/theme
overhaul + a Sberkot assistant, runnable autonomously as parallel batch units.
**Author note:** grounded against current code — several pieces already exist
(`PoolPriceChart`, price-oracle `/ohlcv`, `uiPrefStore` simple-mode), so units
are framed as *enhance* vs *build* accordingly.

---

## Hard environment constraint (drives sequencing)

Docker Desktop has **lost DNS to `registry-1.docker.io`**. BuildKit validates
base-image manifests against the registry on *every* build (even cached), so
**all backend image rebuilds fail right now**. Frontends deploy fine via
`scripts/redeploy-frontends.sh` (host `npm run build` + `docker cp` into nginx —
no registry touch).

⇒ **Two tracks:**
- **Track A — deploy-now (frontend-only):** ships immediately, no registry.
- **Track B — registry-gated (backend):** code-complete now, deploy when DNS
  returns (`docker compose build <svc>` succeeds again). FE consumers of Track B
  build against a mock adapter first (the user-ui already has `mockApi.ts`).

---

## Unit decomposition (11 units)

| # | ID | Title | Layer | Deploy | Depends | Size |
|---|----|-------|-------|--------|---------|------|
| 1 | **T-01** | Theme contrast fix + dual-theme readability audit | FE | now | — | S |
| 2 | **PC-02** | Price chart upgrade (candles, timeframes, surface on Swap/Dashboard) | FE | now | — (uses existing `/ohlcv`) | M |
| 3 | **PC-01** | External market sync (MOEX/FX/crypto feeds → price-oracle) | BE | gated | — | M |
| 4 | **OB-01** | Order book / стакан (bin depth as bid/ask + recent trades) | FE | now | — | M |
| 5 | **LO-01** | Limit-order subsystem (place/cancel/list + price-cross trigger) | BE | gated | — | L |
| 6 | **LO-02** | Limit-order UI (form + open orders + cancel) | FE | now* | LO-01 (mock first) | M |
| 7 | **SM-01** | Simple ⇄ Pro mode (SimpleTradePage, mode switch, nav gating) | FE | now | — | M |
| 8 | **MB-01** | Mobile UI (drawer nav, responsive grids/tables/charts, bottom-bar) | FE | now | — | L |
| 9 | **DS-01** | Design polish pass (spacing, states, both-theme sweep) | FE | now | T-01 | M |
| 10 | **SK-01** | Sberkot mascot (SVG, SberDesign) + assistant widget (contextual hints) | FE | now | — | M |
| 11 | **QA-01** | Coordinator: merge + redeploy + live sweep + report | — | — | all | — |

\* LO-02 renders against a mock until LO-01 deploys; wire to live API in QA-01.

---

## Per-unit specs

### T-01 — Theme contrast fix + readability audit  `[FE, now]`
**Problem (user-reported):** on dark theme the SPOT/CURVE/BID strategy cards are
white-on-dark / unreadable; some labels unreadable on *both* light and dark.
**Files:** `components/StrategySelector.tsx`, `sber-theme.css`, `pages/LiquidityPage.tsx`
(raw `<Tag color="blue">{strategy}</Tag>`), grep for hardcoded colors
(`#6B7280`, `#fff`, `#ffffff`, `#000`, `rgb(`, `color: '#`) across `src/`.
**Do:**
- `StrategySelector`: replace hardcoded `#6B7280`/`#21A038`/card bg with
  `var(--text-secondary)`, `var(--sber-green)`, `var(--bg-card)`,
  `var(--border-light)`; active state via `var(--sber-green)` border/tint that
  works on both themes. `.strategy-card` rules in `sber-theme.css` → CSS-var based.
- Map `LiquidityStrategy` enum to RU labels everywhere it's shown raw
  (Равномерная/Концентрированная/Двусторонняя) instead of `SPOT`/`CURVE`/`BID_ASK`.
- Sweep: every hardcoded text/bg color → CSS var. Verify contrast ≥ 4.5:1 in BOTH
  themes (use `/design:accessibility-review` mental model).
**Done when:** strategy cards + all labels legible in light AND dark; zero
hardcoded grey/black/white text colors left in changed components.

### PC-02 — Price chart upgrade  `[FE, now]`
**Exists:** `components/PoolPriceChart.tsx` consumes `oracle.getOhlcv(poolId)`;
used on `PoolDetailPage`. Backend `/ohlcv/{poolId}?interval=&limit=` is live.
**Do:**
- Candlestick rendering (lightweight-charts if already a dep, else recharts
  Composed/Candle) + line toggle; **timeframe selector** (1м/5м/1ч/1д) → maps to
  `interval` param; volume sub-pane.
- Overlay an **external-market reference line** (from PC-01; until then a clearly-
  labelled "внешний рынок" series from `oracle.getPrice(symbol)` / mock).
- Surface the chart on the **Swap page** (mini) and **PoolDetailPage** (full).
- Empty/loading/error states; `isAnimationActive={false}` (recharts perf rule).
**Done when:** chart shows real candles per pool, timeframe switch refetches,
external reference visible, renders in both themes + mobile width.

### PC-01 — External market sync  `[BE, gated]`
**Service:** `dlmm-price-oracle`.
**Do:**
- `ExternalMarketConnector` interface + adapters: MOEX (equities SBER/GAZP/…),
  FX (SEUR/SCNY/SUSDT pegs), crypto (SBTC/SETH). Each adapter pulls a quote;
  **offline mock adapter** (deterministic walk seeded per-symbol) used when the
  upstream host is unreachable (current sandbox has no external egress) — log
  which adapter is active.
- `@Scheduled` poller (e.g. 30s) → upsert `price_history` + an `external_price`
  field on the price record; expose on `GET /price/{symbol}` as
  `{internal, external, source, asOf}` + a `divergencePct`.
- Kafka `price-events` (optional) so pool-engine can reconcile `basePrice` toward
  external (guard-railed, behind a flag — do NOT auto-move pools this batch).
- Tests: adapter selection, mock walk determinism, divergence calc.
**Done when:** `/price/{symbol}` returns internal+external+source; poller logged;
unit tests green. (Deploy when registry DNS back.)

### OB-01 — Order book / стакан  `[FE, now]`
**Insight:** in DLMM the order book *is* the bin liquidity distribution — bins
below active = bids (Y waiting to buy X), bins above = asks. Data already on
`PoolDetail.bins` / `/pools/{id}`.
**Do:**
- Depth-ladder component: asks (above active, red) stacked descending to spread,
  bids (below, green) ascending — price | size | cumulative, with a depth bar.
- Spread + mid-price header; click a row → prefill swap/limit price.
- "Последние сделки" feed from `transactions` (recent SWAPs on this pool).
- Both themes + mobile (collapsible).
**Done when:** ladder reflects real bin reserves, sums match pool TVL side-totals,
row-click prefills, renders both themes/mobile.

### LO-01 — Limit-order subsystem  `[BE, gated]`
**Service:** `dlmm-pool-engine` (new `limit_orders` table + module).
**Do:**
- Entity `LimitOrder{id,userId,poolId,side,tokenInId,amountIn,targetPrice,
  status(OPEN/FILLED/CANCELLED/EXPIRED),createdAt,filledAt,...}` + Liquibase
  changeset (wrap in `preConditions onFail=MARK_RAN` per repo convention).
- Endpoints: `POST /pools/limit-orders` (place, locks balance via token-service
  deduct→escrow OR reserve flag), `GET /pools/limit-orders/me`,
  `DELETE /pools/limit-orders/{id}` (cancel + refund).
- Trigger: `@Scheduled` evaluator reads current pool price (or consumes
  price-oracle) → for OPEN orders whose `targetPrice` crossed, execute via
  existing `SwapService` path, mark FILLED, emit outbox `LimitOrderFilled`.
- Resilience4j + idempotency keys per repo convention; throw `DlmmException`.
- Tests: place/cancel/refund, cross-trigger fill, no double-fill.
**Done when:** place→cancel refunds exactly; price-cross fills once; balances
conserve (reuse `scripts/consistency-check.sh` style assertions).

### LO-02 — Limit-order UI  `[FE, now* (mock→live)]`
**Files:** Swap/Trade page + new `components/LimitOrderForm.tsx`,
`OpenOrdersPanel.tsx`, `api/limitOrders.ts`.
**Do:** tabbed Market | Limit on the trade surface; limit form (price, amount,
side, est. fill); open-orders table with cancel; toast on fill (poll/notification).
Build against `mockApi.ts` shape until LO-01 is live.
**Done when:** form validates, lists/cancels orders, both themes/mobile; flips to
live API in QA-01.

### SM-01 — Simple ⇄ Pro mode  `[FE, now]`
**Exists:** `store/uiPrefStore.ts` simple-mode flag (hides nav items).
**Do:**
- Promote to `mode: 'simple' | 'pro'` with a prominent header toggle.
- **Simple** = new `SimpleTradePage`: pick token + amount, Buy / Sell, market
  only, one confirm — **no bins, no strategy, no ranges**; routes to the simplest
  swap under the hood. Hide Ребаланс/Команда/advanced liquidity.
- **Pro** = current full UX (bins, strategies, limit orders, order book).
- Persist choice; first-run defaults to Simple with a "перейти в Pro" hint
  (ties into SK-01 Sberkot).
**Done when:** toggle flips the whole trading surface live; Simple buy/sell
executes a real swap; Pro unchanged.

### MB-01 — Mobile UI  `[FE, now]`
**Do:** responsive layer across user-ui:
- Sidebar → hamburger **drawer** under 768px; sticky bottom tab-bar for Simple
  mode (Главная/Обмен/Позиции/Профиль).
- KPI rows stack; wide tables → card-list or horizontal scroll; charts +
  order-book reflow; modals full-screen on mobile.
- Touch targets ≥ 44px; test at 360/390/414 widths.
**Done when:** every primary page usable at 390px, no horizontal overflow, nav
reachable.

### DS-01 — Design polish  `[FE, now]`
**Do (depends on T-01 tokens):** consistent spacing scale + typography rhythm;
unify card/border radii + shadows; standardize empty/loading(skeleton)/error
states; hover/focus states; final dual-theme contrast sweep; tidy the Dashboard
hero + KPI alignment.
**Done when:** visual consistency pass complete, 0 contrast failures either theme,
no console warnings.

### SK-01 — Sberkot assistant  `[FE, now]`
**Mascot:** hand-authored **SVG** in SberDesign style (palette `#21A038`→`#1A8E30`
gradient, rounded geometry, friendly) — *no raster AI-image tool is available in
this environment; SVG is on-brand, scales crisply, themes, and is a tiny payload.*
Provide ~4 expressions/poses (greet, point, idle, celebrate) as one SVG sprite
or component variants. File: `components/sberkot/SberkotMascot.tsx` (+ poses).
**Assistant widget:** floating bottom-right Sberkot; on each route surfaces a
short contextual hint ("Это стакан — клик по строке подставит цену", "Включите
Pro-режим, чтобы выбирать бины", etc.); dismissible; "не показывать снова"
(localStorage `sberkot:seen:<routeKey>`); a few guided tip sequences (first-run
onboarding, simple→pro nudge). Respects reduced-motion. Both themes/mobile.
**Done when:** Sberkot renders crisply both themes, hints are route-aware +
dismissible + persisted, never blocks UI, on mobile docks to a small FAB.

---

## Wave matrix (Track A first; Track B when DNS returns)

Each FE wave = ≤3 parallel agents (isolation worktree). **Only one agent runs
`redeploy-frontends.sh` at a time** — coordinator serializes deploys between waves.

| Wave | Units | Track | Notes |
|------|-------|-------|-------|
| A1 | **T-01**, **PC-02**, **SK-01** | A/FE | independent; T-01 lands theme tokens others reuse |
| A2 | **OB-01**, **SM-01** | A/FE | independent surfaces |
| A3 | **MB-01**, **DS-01** | A/FE | cross-cutting; run after A1/A2 so they polish the new surfaces |
| A4 | **LO-02** | A/FE | mock-backed; wire live in QA-01 |
| B1 | **PC-01**, **LO-01** | B/BE | start when `docker compose build` works; deploy `--no-cache` then `up -d --no-deps` |
| QA | **QA-01** | — | merge all, redeploy FE, deploy BE, live sweep + consistency re-run + report |

---

## Per-agent worker template

```
**Goal:** <unit-id> from docs/FEATURE-BATCH-PLAN-2026-05-29.md (read that unit's spec).
**Branch:** claude/<unit-id>-2026-05-29 off current HEAD.
**Design (mandatory):** follow `docs/DESIGN-DIRECTION-2026-05-29.md` — the
token-level spec + per-surface specs (chart/orderbook/limit/simple/mobile/Sberkot)
+ the 3-tier elevation, spacing scale (`--space-*`), and data-viz palette
(`--viz-*`). Use `/design:accessibility-review` to verify WCAG AA contrast in
BOTH themes before "done"; use `/design:ux-copy` for any user-facing copy.
**Conventions:**
- FE: React 18 + AntD v5 + Vite. **0 hardcoded colors** — CSS vars only
  (--bg-card, --text-primary/secondary/muted, --brand-primary, --border-light,
  --space-*, --viz-*). recharts <Bar/Line isAnimationActive={false}>. Stores via
  useSyncExternalStore (stable snapshot). React Query keys as literals. tsc must pass.
- BE: Java 21, Spring Boot 3.2.5. Throw DlmmException (GlobalExceptionHandler). Kafka via
  OutboxService.append (not kafkaTemplate). Liquibase changeset wrapped in
  preConditions onFail=MARK_RAN. Resilience4j + idempotency on new mutating endpoints.
**Build/verify:**
- FE: cd dlmm-user-ui && npm run build  (tsc must pass). Do NOT redeploy (coordinator does).
- BE: if `docker compose build <svc>` works, build+`up -d --no-deps <svc>`; else report
  "deploy-gated: registry DNS" and leave code committed.
**Finish:** commit + push branch; report `PR/branch: <name>` + what to verify.
```

## Coordinator (QA-01) protocol
1. Merge Track-A branches (theme/T-01 first, then others; resolve user-ui conflicts).
2. `bash scripts/redeploy-frontends.sh` → hard-refresh → sweep every changed surface
   in BOTH themes + at 390px mobile width. 0 console errors.
3. When DNS back: build+deploy PC-01, LO-01; flip LO-02 to live API; re-run
   `scripts/consistency-check.sh` (+ a limit-order conservation check).
4. Update this doc with done-markers; write `docs/FEATURE-BATCH-REPORT-2026-05-29.md`.

## Risks / mitigations
- **Registry DNS (Track B):** code-complete + committed regardless; deploy when net returns.
- **Mobile vs desktop regressions:** MB-01 after A1/A2 so it adapts final markup; sweep both.
- **Limit-order balance escrow** (LO-01): must lock funds on place + refund on cancel —
  reuse the deduct/credit internal endpoints; assert conservation.
- **External feeds offline:** PC-01 mock adapter keeps the feature demoable with no egress.
- **Sberkot scope:** SVG mascot (not raster). If a painted/raster Sberkot is required,
  that needs an external image tool — flag separately.

## Out of scope (this batch)
- F-12 bin-invariant seed fix (separate, needs data-migration cycle).
- F-09 admin-dashboard deploy (registry-gated; lands with Track B).
- ClickHouse OHLCV migration; real broker/MOEX credentials.
