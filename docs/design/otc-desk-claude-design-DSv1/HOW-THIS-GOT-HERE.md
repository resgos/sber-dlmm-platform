# OTC Desk redesign — Claude Design handoff (DS v1)

**Source:** [Claude Design (Anthropic Labs research preview)](https://claude.ai/design)
**Project URL:** `https://claude.ai/design/p/03ea2955-a095-401f-8bba-6a1383f0b479`
**Generated:** 2026-05-20
**Design system attached:** `Sber DLMM Design System` (auto-inherited as Default)

## Why this bundle matters more than the previous three

This is the first Claude Design mockup generated **with a design system
attached**. The previous three bundles in this directory
(`hedge-position-card-claude-design/`, `admin-dashboard-claude-design/`,
`user-dashboard-claude-design/`) were generated *unaware* of our
codebase — Claude had to guess at our sidebar, brand mark, and active-
state styling from the prompt alone, and used a generic chrome that
looked Sber-ish but matched nothing in the actual repo.

For this iteration I:

1. Set up an org-level Design System via *Claude Design → Design
   systems → Create*. Linked `https://github.com/resgos/sber-dlmm-platform`
   over GitHub OAuth, gave a long blurb describing Plasma tokens, the
   AntD + React + Vite stack, and the "Sber green used sparingly" rule.
2. Claude spent ~5 min reading the repo (19 files: `package.json`,
   `sber-theme.css` for both UIs, app shells, component primitives) and
   produced a structured DS inventory: 5 colour categories, 3 spacing
   scales, 8 components, brand assets. Published + Default — auto-
   inherited by every future project in the org.
3. Created a new test project (this one — OTC Desk redesign). The DS
   chip appeared automatically next to *Hi-fi design* / *Interactive
   prototype* in the prompt area, with no extra wiring needed.

## What changed in the output

Side-by-side vs the unaware mockups in the sibling directories:

| Surface | DS-unaware (older bundles) | DS-aware (this bundle) |
|---|---|---|
| Sidebar nav | Invented from scratch (Treasury / Хедж FX / Платежи etc) | **Pixel-match our actual admin-ui**: Дашборд / Пользователи / Токены / Пулы / Транзакции / ОТС деск / Подозрительные / Настройки, with the right badges |
| Brand lockup | Generic "S" tile + "Sber DLMM" | The exact ✓-in-green-square mark + "СБЕР DLMM" wordmark + "Платформа ликвидности" subline that lives in our user-ui |
| Active nav state | Solid green pill | Plasma green-tint background + green text, matches our `--sber-green-light` + `--sber-green` exactly |
| Typography | Inter/Onest at default weights | SB Sans Text stack via our `--font` var |
| Domain copy | Generic ("Treasury Workspace") | Russian institutional vocabulary (RFQ-инбокс, MID, спред, контрагент, маркет-мейкер, hit rate) |
| Counterparty names | Made-up ("Acme Corp") | Russian banking names that *exist* (ВТБ Капитал, Газпромбанк УА, НПФ Газфонд, РСХБ Управление активами, УК «Альфа-Капитал») |

The OTC-specific content is also stronger because the DS gave Claude
the full context of the platform — it added a torgovyj-den header
("✅ ТОРГОВЫЙ ДЕНЬ ОТКРЫТ · Сессия 09:30 — 18:30 MSK · Дежурный ·
Поддержка"), а spread-vs-mid track visualisation showing the current
quote on a ±15 bps axis, and an audit trail with done/current/pending
dots — none of which were in the prompt verbatim.

## How to re-use

The DS is in the org permanently. Any new project in this Claude Design
org will inherit it automatically. The DS itself is at
`https://claude.ai/design/p/b9224794-98fb-41b2-bd64-863bf1f7d372` —
edit its colour ramps, type scale, or component preview cards directly
from that URL and every future generation will pick up the changes.

## Files

- `README.md` — Claude Design's own handoff README
- `chats/chat1.md` — full conversation transcript
- `project/OTC Desk.html` — the page shell that loads the JSX modules
- `project/otc.jsx` — main page composition
- `project/Primitives.jsx` — Card / DeltaPill / StatusPill / TokenChip etc, lifted from the DS
- `project/Icons.jsx` — shared icon set
- `project/colors_and_type.css`, `project/kit.css`, `project/otc.css` — token + base + page styles
- `project/assets/sber-logo.svg` — brand mark Claude reconstructed from our user-ui screenshot
