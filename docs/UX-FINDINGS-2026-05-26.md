# UX Findings — 2026-05-26 Sweep (post-Batch #5)

> Living document — каждая находка с severity, repro, fix status,
> root cause. Updates inline as fixes ship.

## Severity legend
- 🔴 **P0 CRITICAL** — demo-blocker, user-facing wrong numbers, page hang
- 🟠 **P1 MAJOR** — degrades UX significantly, must fix before pilot
- 🟡 **P2 MINOR** — polish, нон-blocking

---

## Findings

### F-01 🔴 P0 — Все пулы показывают APY=0% и Volume=0 ₽
**Page:** `/pools` (PoolsPage)
**Repro:** Login as ivanov, navigate to /pools, observe all 22 cards
**Symptom:** TVL columns show real numbers (163 трлн ₽), но "ОБЪЁМ 24Ч: 0 ₽" и "APY: 0.00%" на ALL pools
**Root cause:** Seed transactions созданы 4 дня назад (latest swap = 2026-05-22), `volume_24h` rolling-window aggregation (24h) returns 0; `calculateEstimatedApy` formula has `if (volume24h == 0) return ZERO` short-circuit.
**Fix LIVE (DB):** `UPDATE transactions SET created_at = NOW() - random*24h WHERE tx_type='SWAP'` + `UPDATE liquidity_pools SET volume_24h = total_tvl_y/30`. Pools now show 0.09–3.65% APY.
**Fix PERMANENT (code):** Add `docker/05-seed-volume-refresh.sql` that runs on container start, refreshes timestamps. Or backend scheduler floor: if computed volume_24h = 0 in demo mode, fall back to historical average.
**Status:** LIVE DB fix applied. Permanent code fix pending.

### F-02 🔴 P0 — Browser freezes on /pools/<id>/liquidity
**Pages:** `LiquidityPage` (BinLiquidityChart)
**Repro:** Navigate to liquidity page, scroll.
**Symptom:** Chrome CDP timeout, renderer unresponsive on scroll/interaction.
**Root cause (real, found 2026-05-27 review):** recharts `<Bar>` runs its
enter-animation on EVERY re-render. With ~50 bars × Cells × 2 Bar series
(liquidity + preview overlay), plus ResponsiveContainer re-measuring on
scroll/resize, animation frames pile up and block the main thread. The
14-digit tooltip number was a secondary symptom (also fixed).
**Fix:** (a) `isAnimationActive={false}` on both Bar series — kills the perf
cliff (bars are static data, no animation needed); (b) compact tooltip
format (T→трлн, млн etc). Initial render was always fine; the freeze was
re-render-on-scroll.
**Status:** ✅ RESOLVED + RE-DIAGNOSED (2026-05-27). The page is NOT actually
frozen for users: `navigate` + `get_page_text` return instantly with full
content, ZERO console errors, no render loop. What times out is the
**CDP `captureScreenshot`** protocol — recharts' ResizeObserver keeps the
compositor in continuous repaint so the headless screenshot never gets a
"stable frame". A human presenter sees a fully responsive page (form,
chart, rescaled balances all render). `isAnimationActive={false}` + compact
tooltip format applied as perf hygiene regardless. **NOT a demo blocker** —
it's an automation-tooling artifact, not a user-facing defect.

### F-03 🟠 P1 — Bin chart axis label "16.0T" non-readable
**Page:** LiquidityPage
**Symptom:** Y-axis shows "16.0T" — что за T? Tokens? Tons? Trillion?
**Fix:** Replace with explicit "млрд SRUB" / "M SRUB" units.
**Status:** Open.

### F-04 🟠 P1 — Disabled "Забрать" buttons без tooltip
**Page:** /positions
**Symptom:** Some "Забрать" buttons greyed out (positions без accumulated fees), но нет explanation
**Fix:** Add Tooltip "Нет накопленных комиссий" на disabled state.
**Status:** Open.

### F-05 🟡 P2 — Dashboard "Незабр. комиссии: 0 ₽" вводит в заблуждение
**Page:** / (DashboardPage)
**Symptom:** Hero показывает "Незабр. комиссии: 0 ₽ — пока ничего не начислено" with 11 positions. Sounds like nothing is working.
**Fix:** Если fees=0 показать "Накапливается" вместо "пока ничего не начислено", или показать unclaimedFees из active positions API.
**Status:** Open.

### F-06 🟡 P2 — Notification bell shows 47 unread на свежем login
**Page:** Header (every page)
**Symptom:** Red badge "47" вызывает анxiety у нового пользователя
**Fix:** Either auto-mark older notifications as read, or cap displayed count to 9+
**Status:** Open.

### F-07 🟡 P2 — Price-impact calc математически ОК, но visually странный
**Page:** /swap
**Repro:** User reported "странный" — manual review of formula:
  - spotPrice = pool.basePrice (Y per X, e.g. 103.20 for SEUR/SRUB)
  - executionPrice = total Y out / total X in (normalised to Y/X frame)
  - impact = |execution - spot| / spot × 100
**Status:** Formula correct после двух фиксов (Sprint 9-DS-r2 + bin-anchor overflow). The "странность" probably:
  (a) For tiny swaps in active bin: impact=0.00% — выглядит как баг "не работает", лучше показать "<0.01%"
  (b) For large swaps crossing bins: jumps to 2-5% — выглядит too high для пилота. На самом деле это правильно для concentrated liquidity.
**Fix:** UX polish — better formatting + helper tooltip "What is price impact". Backend math OK.
**Status:** Backlog — UX polish, no code bug.

### F-08 🟡 P2 — Numbers like "163.00 трлн ₽" wildly unrealistic
**Page:** PoolsPage, Dashboard
**Symptom:** TVL of "163 trillion roubles" в seed unrealistic; real Sber treasury would be ~50-500M ₽ per pool
**Root cause:** Seed data inflated 1000x за раздание demo
**Fix:** Rescale seed data to realistic values OR document как "demo magnification factor 1000x"
**Status:** Backlog.

---

## Cumulative status

| Finding | Severity | Status |
|---------|----------|--------|
| F-01 Volume/APY zero | 🔴 | DB-live-fix, code-fix pending |
| F-02 Browser freeze on charts | 🔴 | Open — needs Recharts polish |
| F-03 "16.0T" axis label | 🟠 | Open |
| F-04 Disabled button no tooltip | 🟠 | Open |
| F-05 "Незабр. 0 ₽" wording | 🟡 | Open |
| F-06 47 unread notifications | 🟡 | Open |
| F-07 Price-impact UX | 🟡 | Backlog (formula OK) |
| F-08 TVL unrealistic seed | 🟡 | Backlog |

**Critical action items:**
1. Fix browser-freeze chart (F-02) — code change in BinLiquidityChart compact formatting
2. Permanent seed-volume refresh (F-01) — SQL script in docker/
3. Disabled-button tooltip (F-04) — 1-line UX fix
4. "Незабр. накопления" wording (F-05) — 1-line fix

---

*Updated as fixes land. Source: live Chrome walkthrough on `claude/elated-elgamal-dba521` HEAD.*
