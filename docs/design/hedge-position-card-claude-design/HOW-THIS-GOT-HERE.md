# Open Hedge Position Card — Claude Design handoff

**Source:** [Claude Design (Anthropic Labs research preview)](https://claude.ai/design)
**Project URL:** `https://claude.ai/design/p/a6f65acf-45d3-4df2-a875-95666e6ed807`
**Generated:** 2026-05-19
**Author:** Ruslan via Claude Design, exported via *Share → Handoff to Claude Code*

## What this is

A vanilla HTML/CSS/JS prototype of a polished "Open hedge positions" page
for the user-ui's Хедж FX section. Generated end-to-end by Claude Design
from a one-shot prompt + 4 quick clarifying answers (collapsed-expandable
layout, USD/EUR/CNY/RUB pairs, plain FX-forward shape, "Decide for me"
on the rest).

This bundle is **reference material**, not production code. The user-ui
uses React 18 + AntD 5; this prototype uses inline `<script type="text/babel">`
to avoid a build step. Don't `import` from here.

## What we actually shipped from it (Sprint 9)

Cherry-picked into `dlmm-user-ui/src/sber-theme.css` and `index.html`:

1. **Onest font** for Cyrillic-friendly fintech typography (was system
   font fallback). Loaded from Google Fonts with `preconnect` for fast first paint.
2. **`font-variant-numeric: tabular-nums`** as a body-wide default
   so digits in tables line up. Free 10× win for any financial UI.
3. **Warm off-white page background `#FAFAF8`** (was pure `#FFFFFF`).
   Reduces glare without losing the bank-clean feel.

The bigger structural pieces (position card grid, sparkline component,
close-confirm popover, tweaks panel) are not yet integrated — that's a
follow-up when the platform actually has open-hedge data to render
against. Right now the Hedge page shows `Открытые хеджи 0` and the
list is empty, so the card visual is unobservable in production.

## How to re-use this bundle

If you want to extend the design later:

1. Open `https://claude.ai/design/p/a6f65acf-45d3-4df2-a875-95666e6ed807`
   (Ruslan's account) — the original project is still editable.
2. Iterate via chat or inline comments. Each iteration is captured in
   `chats/chat1.md` for traceability.
3. Re-export via *Share → Handoff to Claude Code* and replace this
   bundle.

## Why the prototype renders flat-HTML

Claude Design intentionally outputs prototypes (HTML + inline React via
Babel-standalone) rather than framework-specific code. The README in
the bundle (`README.md`) explicitly says: *"recreate pixel-perfectly
in whatever technology makes sense for the target codebase. Match the
visual output; don't copy the prototype's internal structure unless it
happens to fit."*

So treat `project/app.jsx` as a visual contract + algorithmic spec
(e.g. the `Sparkline` SVG drawing logic, the number formatters, the
delta-pill colour rules), not as code to copy verbatim.

## Files

- `README.md` — the original Claude Design handoff README
- `chats/chat1.md` — full conversation transcript (intent log)
- `project/index.html` — design tokens, layout, page chrome
- `project/app.jsx` — `PositionCard`, `Sparkline`, `StatsStrip`, data
- `project/tweaks-panel.jsx` — runtime UX-tweaks panel (density, accent,
  dark mode) — not for production, useful only inside Claude Design canvas
