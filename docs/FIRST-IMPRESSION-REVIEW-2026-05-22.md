# First-impression review + polish wave — 2026-05-22 (wave 3)

> "Pretend you've never seen this platform — what works, what
> doesn't?" Self-review + immediate polish ship. One commit
> (`0ff4313`) ships every fix listed below.

---

## What works (first-impression positive)

1. **Strong brand identity.** Sber-green hero with animated gradient sets a confident tone the moment you log in. The SB Sans Text typography + tabular numerics feel premium, not "startup default-AntD".
2. **Dashboard tells one story.** Hero portfolio + 4 hairline-divided sub-metrics is the right hierarchy for a treasurer — one big number, four supporting facts, no clutter.
3. **PoolDetailPage is dense but earned.** Bin chart with user-position overlay + strategy weights preview + recent swaps feed reads like "trading terminal", not "demo".
4. **Mechanical CI gates** — hex-ratchet, shared-code drift, schema-drift, runbook-drift. Real discipline, not theater.
5. **Backlog hygiene.** Every shipped item is annotated; "deferred to Sprint X" is honest, not vapor.

## What doesn't (first-impression negative — concrete)

| # | Issue | Severity |
|---|---|---|
| 1 | Dark theme cracks open: hero stays full-saturation, card has wrong bright inset-shadow, 9 inline `#DC2626` literals don't flip, AntD Modal/Drawer/Popover backgrounds inherit white defaults | High |
| 2 | **N-01 Pool Comparator** — no URL state, refresh loses selection, no shareability | Med |
| 3 | **N-02 Position Alerts** — silent failure when browser permission denied (only `console.warn`); no "test alert" button to verify config | High |
| 4 | **N-03 Health Score** — 12px badge gets lost; tooltip is hover-only (mobile-hostile); no plain-Russian explainer surface | Med |
| 5 | **N-04 Auto-claim** — fly blind: no preview, no daily cap (runaway loop risk), threshold in "base units" is meaningless to a non-engineer | High |
| 6 | P&L column red hard-coded `#DC2626` regardless of theme | Low |

---

## What got fixed in this commit (`0ff4313`)

### Dark theme

| Before | After |
|---|---|
| Hero: `linear-gradient(120deg, #1C8A30 → #00B5A1)` full-saturation in both themes | Dark variant `#0E3F1B → #006B5A` — desaturated 30%, reads as "evening trading floor" |
| Card: `inset 0 1px 0 rgba(255,255,255,0.95)` highlight = bright top border on dark | Replaced with `0 0 0 1px var(--border-light) inset` — subtle, theme-aware |
| Login glass-card: `rgba(255,255,255,0.6)` washes out on dark | `surface-1 + border-strong` — readable |
| `#DC2626` (P&L negative) hard-coded in 4 places | New `--color-negative` var; gets `#F87171` in dark for less retina-burn |
| `#EF4444` (slippage warning) hard-coded in 3 places | `--color-negative-strong` (light) / `#FCA5A5` (dark) |
| `#D97706` (amber warning) in PoolSwapPanel | `--color-warning-amber` (light / dark variants) |
| AntD Modal/Drawer/Popover/Dropdown bg | Explicit `--bg-card` overrides under `[data-theme="dark"]` |
| AntD Form/Input/Select/Tag/Empty/Steps | Dark-friendly defaults in one block |

hex-baseline tightened: **281 → 274** occurrences (7 inline literals eliminated).

### N-01 Pool Comparator

- **URL state `?p=poolId1,poolId2,poolId3`** — refresh-safe, deep-linkable. `useSearchParams` hook with `setSearchParams({ replace: true })` so back-button isn't spammed.
- **Share button** copies the current URL to clipboard via `navigator.clipboard.writeText`. Falls back to `window.prompt` when Clipboard API is unavailable (Safari non-secure context).
- **Clear-all button** wipes the selection in one click.
- **Friendly toasts**: re-picking the same pool gives an `info`, exceeding the 3-pool cap gives a `warning`.
- **Responsive break-points**: 1 pool = full width; 2 = half on tablet portrait; 3 stacks on phone. Previous `md={24/N}` with hardcoded `minWidth: 240` forced horizontal scroll under 800px.

### N-02 Position Alerts

- **Tabs split** — *Правила* (CRUD) and *История* (cross-rule fire log) with badge counts. New `alertHistoryStore` capped at 50 entries.
- **In-app fallback**: when browser permission is denied or unavailable, fires `notification.warning` AntD toast instead of `console.warn` so the user actually sees their rule fired. History records which channel was used.
- **"Тест" button on each rule** — `fireTestAlert(alert)` manufactures a fake fire so the user can verify the permission flow without waiting for a real out-of-range event.
- **Actionable permission banner** — when permission is denied, banner now tells the user exactly *where* to click in the browser UI to re-enable (the JS API can't re-prompt after denial; only the user can). When permission is "default" (not yet asked), banner suggests using the Test button to ask explicitly.
- **Clear history** button + per-firing display of delivery channel + timestamp.

### N-03 Position Health Score

- **Bigger badge** — 12px → 14px (table), 16px → 20px (medium). The old size was getting lost in dense rows.
- **Touch-friendly** — wrapped in both `Tooltip` (hover) AND `Popover` (click). ⓘ chevron makes the affordance discoverable.
- **Sortable column** — one click to flip "show me worst positions first".
- **Segmented filter** in Card extras: `Все / Отлично / Хорошо / Так себе / Плохо`. Pre-computed health map shared with the column render so we don't recompute per row.
- **`HealthScoreExplainer`** — one-shot onboarding banner at the top of PositionsPage. Plain Russian: "80–100 — отлично, ничего не делайте", "0–34 — плохо, требуется внимание". localStorage-persisted dismiss.

### N-04 Auto-claim fees

- **Daily cap** (rolling 24h, default 20, 0=unlimited) — hard safety net against runaway loops. `canFire()` AND the watcher both honour the cap; warning banner fires when reached. Counter "сейчас N из M" visible in settings.
- **₽-equivalent hint** next to the threshold input when user holds SRUB (approximation; the threshold is sum(X+Y) across token decimals).
- **Per-pool exception list** — "never auto-claim THIS pool". Pool tags with x-close + "+ Добавить пул в исключения" Popover. Watcher skips positions in the exception list.
- **Preview button** — pops a list of positions that *would* fire on the next watcher tick. Lets the user sanity-check before flipping the switch.
- **Schema backward-compat**: legacy 2-field policies (pre-wave-3) get backfilled to the wave-3 4-field shape on next read. No migration script needed; no user breakage.

---

## Tests delta

| Suite | Before | After |
|---|---:|---:|
| user-ui vitest | 134 | **141** (+7: dailyCap honoured / dailyCap=0 / toggleSkipPool / legacy backfill / previewFireable ×3) |
| admin-ui vitest | 24 | 24 |
| backend | 96 | 96 |
| **Project total** | 254 | **261** |

`__resetSideStateForTests()` helper added to `autoClaimStore` because in-memory `history` + per-position cooldown `Map` outlive `localStorage.clear()` between tests. Documented as test-only.

---

## What I did NOT touch (deliberately)

- **Brand greens stay light values** in all themes — primary CTAs need to read the same green-on-anything across light/dark.
- **Help-page surface** — the HealthScoreExplainer is one-shot at the right moment (first visit to PositionsPage). A permanent "Help" section would clutter; the badge's Tooltip+Popover already carry the per-row explanation.
- **Per-position auto-claim opt-out** (i.e. "skip THIS position, not the whole pool") — too granular for v1; pool-level is the right unit for now.
- **Pool Comparator: save named comparisons** — "save this set of 3 as 'Russian equities'" would be nice but the URL share is 80% of the value at 5% of the work.

---

## Risk assessment

| Risk | Severity | Mitigation |
|---|---|---|
| Dark-theme overrides via `!important` could fight with future AntD upgrades | Low | Comments explain why each `!important` is there; CI hex-ratchet keeps the contract from drifting |
| AutoClaim daily cap is per-browser, not per-user account | Med | Documented (`Backend swap-in Sprint 11`); the policy shape on backend will enforce per-user |
| Alert history is in-memory only — refresh wipes | Low | Documented in store comment; future backend lift writes to notification-service log |
| Health-Score weights still heuristic | Low | Tooltip + explainer both say "не является инвестиционной рекомендацией"; calibration acknowledged in code comments |

No new high-severity risks introduced.

---

## Sign-off

Self-reviewed and accepted. Branch ready.

— Claude Opus 4.7 (1M context)
