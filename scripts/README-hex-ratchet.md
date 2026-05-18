# `check-no-hex-in-tsx.mjs` — Sprint 8 AU-2 design-token ratchet

**Audit reference**: `docs/SYSTEM-AUDIT-2026-06-17.md` AU-2 / C-3.

## What it does

Scans `dlmm-user-ui/src/**/*.tsx` + `dlmm-admin-ui/src/**/*.tsx` for hex
color literals (`#RGB` / `#RRGGBB` / `#RRGGBBAA`). Compares per-file count
against `scripts/hex-baseline.json`. Fails if any file's count goes up,
or a new `.tsx` file is added with hex.

The intent is **strictly-decreasing**: today's baseline is 243 occurrences
across 34 files; every refactor that drops a hex literal (replacing with
`var(--sber-*)` or a CSS class) should be followed by a `--update` pass
to tighten the ceiling.

## Why a ratchet, not strict "no hex"

System audit measured 209 hex occurrences (this script's more thorough
count is 243). Strict "no hex anywhere" would fail every PR until the
top-10 files are swept (UX-DS-1, ~2d). Until then the ratchet keeps the
gate live without blocking unrelated work.

## Usage

```bash
node scripts/check-no-hex-in-tsx.mjs            # CI mode (exit 1 on regression)
node scripts/check-no-hex-in-tsx.mjs --report   # print top 15 offending files
node scripts/check-no-hex-in-tsx.mjs --update   # regenerate baseline after a sweep
```

Equivalent npm scripts (from inside either UI):
```bash
npm run lint:colors
```

## Allowlist

Files that legitimately contain hex (source-of-truth palette literals)
are listed in `ALLOWLIST` inside the script:

- `dlmm-user-ui/src/components/TokenChip.tsx` — 8-colour palette per token
- `dlmm-user-ui/src/main.tsx` / `dlmm-admin-ui/src/main.tsx` — root theme application

Add a file here **only if** the hex is structurally unavoidable (palette
generator, theme bootstrap). The default answer is "no, use CSS vars".

## CI wiring

`.github/workflows/frontend.yml` → `hex-ratchet` job runs first; `ui`
matrix + `e2e` job depend on it. Regression fails fast (≤30s) before
the slower test/build matrix spends minutes.

## Workflow when you legitimately need a new colour

1. Add the variable to `dlmm-user-ui/src/styles/sber-theme.css` (or
   the equivalent admin theme file)
2. Use `var(--your-token)` in the `.tsx`
3. Re-run `node scripts/check-no-hex-in-tsx.mjs` — should still pass

## Workflow when you sweep a top-offender file

1. Replace hex with `var(--…)` or a CSS class
2. Run `node scripts/check-no-hex-in-tsx.mjs --update` to tighten baseline
3. Commit the updated `scripts/hex-baseline.json` alongside the file edit

## Tracked baseline progression

| Sprint 8 day | Files | Total hex | Δ vs Day 1 |
|---|---|---|---|
| Day 1 (baseline) | 34 | 243 | — |
| Day 4 (UX-DS-1 first sweep) | 32 | 207 | −14.8% |

**Day 1 top 5 offenders:**
- `dlmm-admin-ui/src/components/BinLiquidityChart.tsx` (22)
- `dlmm-admin-ui/src/pages/DashboardPage.tsx` (22) → **swept to 0**
- `dlmm-user-ui/src/components/BinLiquidityChart.tsx` (19)
- `dlmm-user-ui/src/components/UserLayout.tsx` (15)
- `dlmm-user-ui/src/pages/RegisterPage.tsx` (15)

**Day 4 top 5 (post-sweep):**
- `dlmm-admin-ui/src/components/BinLiquidityChart.tsx` (22)
- `dlmm-user-ui/src/components/BinLiquidityChart.tsx` (19)
- `dlmm-user-ui/src/components/UserLayout.tsx` (15)
- `dlmm-user-ui/src/pages/RegisterPage.tsx` (15)
- `dlmm-admin-ui/src/components/ProtectedLayout.tsx` (15)

The BinLiquidityChart files are recharts-driven; the recharts API takes
hex strings directly for fill/stroke, so those need either a chart-color
const module or an opt-in allowlist entry. Sprint 9 work.

Sprint 8 UX-DS-1 stretch goal: drop to ≤ 180 (further user/admin
Layout + RegisterPage sweep) — Day 5+ work if time permits.
