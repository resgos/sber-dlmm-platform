# Design Direction — Sber DLMM (2026-05-29)

Produced via `/design:design-critique`. This is the **north-star spec** every
UI unit in `FEATURE-BATCH-PLAN-2026-05-29.md` must follow. Token names map to
`dlmm-user-ui/src/sber-theme.css`.

---

## Design Critique: DLMM user-facing trading UI

### Overall impression
Not "utilitarian AntD" — there's a genuinely mature token system already
(brand roles, Plasma palette, dual-theme overrides, 7-step type scale, shadow +
radius ramps). The gap is **adoption + a few missing scales**, not foundations.
Biggest opportunity: enforce the tokens everywhere (kill hardcoded colors → fixes
dark-theme contrast for free), add a **spacing scale** + **data-viz palette**, and
lift density into a calmer, "private-banking" rhythm.

### Usability
| Finding | Severity | Recommendation |
|---|---|---|
| Strategy cards (SPOT/CURVE/BID) unreadable on dark — hardcoded `#6B7280`/`#21A038`/white card bg bypass tokens | 🔴 Critical | Re-token to `var(--text-secondary)`/`var(--brand-primary)`/`var(--bg-card)`; active = brand tint + 2px brand border |
| Raw enum text leaks to users (`<Tag>{strategy}</Tag>` → "SPOT") | 🟡 | Map enum → RU label everywhere (Равномерная/Концентрированная/Двусторонняя) |
| Bins/ranges are intimidating for casual users | 🟡 | This is exactly what Simple mode (SM-01) solves — Simple hides all bin UI |
| Tables dense, no breathing room | 🟡 | Row height 44→52px, cell padding via new `--space-3`, zebra via `--surface-1` |

### Visual hierarchy
- **What draws the eye first:** the green hero — correct, but the portfolio number competes with 3 KPI tiles at similar weight. Make the portfolio value `--text-display` (48px/800) and demote the 3 KPIs to `--text-lg`/secondary labels so there's ONE hero number, then a supporting row.
- **Reading flow:** hero → quick actions → data. Good skeleton. Tighten by giving each section a consistent 24px (`--space-5`) vertical rhythm and a single H2 style (`.sber-section-title`).
- **Emphasis:** too many cards at equal elevation (`--shadow-sm` everywhere). Use a 3-tier elevation language (below) so primary surfaces pop and secondary recede.

### Consistency
| Element | Issue | Recommendation |
|---|---|---|
| Spacing | No `--space-*` scale → inline 8/12/16/20/24 everywhere | **Add a spacing scale** (below) and migrate |
| Color | Hardcoded `#6B7280`, `#fff`, `#1F2937` (ConfigProvider), greys bypass tokens | Sweep → CSS vars; this is the root of dark-theme bugs |
| Data-viz | No candle/bid-ask/depth colors → new chart+orderbook would invent ad-hoc colors | **Add data-viz tokens** (below) |
| Cards | radius mix (8/12/18) applied ad hoc | Standardize: KPI/data card = `--radius-md`(12), hero/feature = `--radius-lg`(18), pills = `--radius-pill` |

### Accessibility
- **Contrast:** light theme generally passes; **dark theme fails** wherever hardcoded colors are used (strategy cards, any `#6B7280` on `#131820` ≈ 2.0:1). After re-tokenization, `--text-secondary` (rgba white .60) on `--bg-card` ≈ 7:1 ✅. ConfigProvider `colorText:'#1F2937'` must become theme-reactive.
- **Touch targets:** sidebar items + table actions < 44px on mobile → MB-01 must enforce 44px min.
- **Charts:** never encode up/down by color alone — pair with ▲/▼ + sign (color-blind safe).

### What works well
- Mature semantic token system + dual-theme machinery already in place.
- Brand discipline: one green, gradient reserved for hero/CTA, `--text-on-brand` solves text-on-green.
- Thoughtful prior critiques (typography scale, brand-role split) — we extend, not rebuild.

---

## Token additions (add to `sber-theme.css` in T-01, both themes)

### 1. Spacing scale (NEW — biggest consistency win)
```css
--space-1: 4px;  --space-2: 8px;  --space-3: 12px; --space-4: 16px;
--space-5: 24px; --space-6: 32px; --space-7: 48px; --space-8: 64px;
```
Rule: no raw px for margin/padding/gap in new code — use `--space-*`. Page section
rhythm = `--space-5` (24). Card padding = `--space-4` (16) compact / `--space-5` roomy.

### 2. Data-viz palette (NEW — chart + order book must share these)
```css
:root{
  --viz-up:   #0D8523;  --viz-up-soft:   rgba(13,133,35,0.12);   /* candle up / bid */
  --viz-down: #E31227;  --viz-down-soft: rgba(227,18,39,0.12);   /* candle down / ask */
  --viz-grid: var(--border-light);
  --viz-axis: var(--text-muted);
  --viz-external: var(--sber-violet);  /* external-market reference line */
}
[data-theme="dark"]{
  --viz-up:#2FBF50; --viz-down:#FF5468;          /* lift saturation for dark bg */
  --viz-up-soft:rgba(47,191,80,0.16); --viz-down-soft:rgba(255,84,104,0.16);
}
```
Bid = up-green, Ask = down-red, depth bars use the `-soft` fills. Always pair with ▲/▼.

### 3. Elevation language (use the existing shadow ramp deliberately)
- **Tier 0 (recede):** page bg `--bg-page`, no shadow — backgrounds, table bodies.
- **Tier 1 (default card):** `--bg-card` + `--shadow-sm` + `--radius-md` — KPI/data cards.
- **Tier 2 (feature/active):** `--shadow-md` + `--radius-lg` + 1px `--brand-primary` border when selected — hero, active strategy, primary panel.
- **Tier 3 (overlay):** `--shadow-lg` — modals, drawers, Sberkot bubble.

---

## Per-surface styling specs (for the batch units)

**Price chart (PC-02):** dark-on-dark / light-on-light surface = `--bg-card`; candles
`--viz-up`/`--viz-down`; grid `--viz-grid`; external line dashed `--viz-external`;
timeframe selector = AntD `Segmented` (sized `--space-3` text-sm); volume sub-pane
`--viz-*-soft`. No animation (recharts perf rule).

**Order book / стакан (OB-01):** two-color ladder — asks (top, `--viz-down` text +
`--viz-down-soft` depth bar, descending to spread), bids (bottom, up-green). Mid-price
chip centered with spread %. Monospace/tabular-nums for all prices+sizes
(`font-variant-numeric: tabular-nums`). Row hover `--surface-1`, click → prefill.

**Limit orders (LO-02):** Market | Limit as AntD `Segmented` tabs on the trade card.
Buy CTA = `--viz-up` solid, Sell = `--viz-down` solid (NOT brand-green — green means
"buy" here; keep brand-green for nav/identity only on this surface). Open-orders rows
get a status dot (`--viz-up`/`--text-muted`/`--viz-down`).

**Simple ⇄ Pro (SM-01):** mode toggle = pill `Segmented` in the header
(`--radius-pill`). Simple surface is airy: big token picker, one amount field, a single
**Купить/Продать** segmented control, one primary CTA at Tier-2 elevation, generous
`--space-6` padding. Pro = current density. Visually signal mode with a small chip
(«Simple» grey / «Pro» brand-tint) so users always know where they are.

**Mobile (MB-01):** ≥44px touch targets; sidebar→drawer; Simple mode gets a fixed
bottom tab-bar (`--bg-card` + `--shadow-lg` top, 4 icons). Cards full-bleed with
`--space-4` gutters. Charts/orderbook reflow to full width, modals full-screen.

**Sberkot (SK-01):** SVG mascot, palette = `--grad-brand` body accents on a
white/`--bg-card` belly, friendly rounded geometry (no sharp angles), 2px brand
outline. Assistant bubble = Tier-3 overlay, `--radius-lg`, `--bg-card`, tail pointing
to the mascot; hint text `--text-paragraph` `--text-sm`; dismiss = ghost button.
Bubble accent bar = `--brand-primary`. On mobile, mascot = 48px FAB bottom-right above
the tab-bar. Respect `prefers-reduced-motion` (no bounce).

---

## How the design skills plug into the batch (mandatory gates)

| Skill | When | Applied to |
|---|---|---|
| `/design:design-critique` | DONE (this doc) | sets direction for all units |
| `/design:design-system` | inside **T-01** | add spacing + data-viz tokens, document the 3-tier elevation + radius rules; output a short tokens reference |
| `/design:accessibility-review` | inside **T-01** + **QA-01** | WCAG AA contrast on BOTH themes for every changed surface; touch targets in MB-01 |
| `/design:ux-copy` | inside **SK-01**, **SM-01**, **DS-01** | Sberkot hint copy, Simple-mode labels/CTAs, empty/error/loading states |

Each UI agent's "done" criteria now include: **0 hardcoded colors** (tokens only),
**passes the dual-theme contrast check**, and **follows the per-surface spec above**.
