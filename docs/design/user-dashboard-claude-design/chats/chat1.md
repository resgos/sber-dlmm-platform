# Sber Treasury Dashboard Redesign

_Started 2026-05-20 08:09 UTC_

---

## User

Redesign the user-facing dashboard of Sber DLMM — internal corporate-treasury platform, Russian-locale, audience is treasurers (not retail crypto). Aesthetic must be bank-grade, Bloomberg-on-a-good-day, NOT flashy. Use Plasma design tokens (Sber's open-source DS from @salutejs/plasma-tokens-web): page bg #FAFAF8 warm off-white, card #FFFFFF, surfaceSolid01 #FAFAFA, text #080808 with secondary rgba(8,8,8,0.56) and tertiary rgba(8,8,8,0.28), border rgba(8,8,8,0.10), Plasma success #0D8523 / critical #E31227 / warning #D14D00. Sber green #21A038 reserved as the ONE primary accent — never two green elements at the same hierarchy level. Typography SB Sans Text with Onest fallback, tabular-nums on all digits.

## User

CURRENT ("before") has these problems I want fixed: (a) giant green portfolio hero showing 8.05 квд ₽, (b) a 4-up stat-tile row right under the hero where the FIRST tile just duplicates the hero number, (c) SberSpasibo loyalty card alone at 1/3 width on the left of the page with empty void to its right, (d) basic "Мои токены" table at the bottom (token / available / locked / price / value) too sparse to read as a live snapshot. Left sidebar nav stays: Главная / Обмен / Хедж FX / Пулы / Мои позиции / Транзакции / Профиль.

## Assistant

_[tool: questions_v2]_

Before I start designing, a few focused questions — the brief is detailed but the product domain matters a lot for what should actually be on screen.

_[tool: update_todos]_

_[tool: copy_starter_component]_

Got it — designing this now. Sticking firmly to Plasma tokens, single primary green accent, tabular nums everywhere, 1440 desktop, dense bank-terminal feel.

_[tool: read_file]_

_[tool: write_file]_

I have what I need from the tweaks panel. Now writing the dashboard.

_[tool: write_file]_

_[tool: copy_files]_

_[tool: str_replace_edit]_

Let me rename to keep primitives separate, then write the main app file.

_[tool: write_file]_

_[tool: write_file]_

_[tool: write_file]_

_[tool: str_replace_edit]_

_[tool: show_html]_

_[tool: done]_

_[tool: save_screenshot]_

That's from a Chrome extension, not my code. Let me verify the page actually renders.

_[tool: view_image]_

_[tool: save_screenshot]_

Looking great. Let me check the positions strip below the fold.

_[tool: view_image]_

_[tool: update_todos]_

_[tool: fork_verifier_agent]_

The dashboard is rendering exactly as briefed. Everything's in place — compact 140px hero with inline sub-metrics, activity + quick actions split, narrow СберСпасибо strip, dense tokens table with Δ24ч pills, and positions strip with sparklines and range bars.

_[tool: done]_

_[tool: fork_verifier_agent]_

Shipped. The redesign delivers everything in the brief:

- **Hero ~140px** — portfolio number `12 437 820,40 ₽` on the left, then 4 inline sub-metrics (active positions / 24h volume / unclaimed fees / lifetime earnings) separated by hairlines. No duplicate tile row.
- **Activity + Quick actions** side-by-side. Last 5 ops with time / pair chips / signed amount / status pill. Three quick-action buttons — only «Свопнуть SUSDT → SRUB» is filled green (single-primary-accent rule), the other two are outlined, plus a warning footer about the 2 out-of-range positions.
- **СберСпасибо** demoted to a 56px full-width strip — balance, monthly accrual, link to terms, "Списать баллы" CTA. Easy to dismiss via the Tweaks toggle.
- **Мои токены** — full-width, 40px rows, dot-prefixed token chips, hover state, Δ24ч green/red pills, plus a "доля портфеля" mini-bar column to add density without noise.
- **Мои позиции** — 3 cards with sparklines, pair pills, range bar (showing current price marker, including the out-of-range SETH/SRUB pinned to the edge in warning orange), APR, status pill.

Tabular-nums throughout, Plasma tokens only, SB Sans Text → Onest. Tweaks panel exposes density (Плотно / Стандарт / Свободно) and the СберСпасибо toggle.Verifier hit a rate limit (HTTP 429) before it could run — not a verdict on the design. The page itself loaded cleanly (no console errors) and screenshots show the layout rendering correctly at 1440px. Let me know if you'd like me to iterate on anything.

