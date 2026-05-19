# # DLMM Admin Dashboard

_Started 2026-05-19 13:37 UTC_

---

## User

Design a polished Sber DLMM admin dashboard (Russian-locale corporate banking aesthetic). It oversees a tokenised liquidity platform with 22 active pools, 4 users, transaction history, KYC verification. Current rendition is ugly: a giant green gradient hero with one number, then eight flat stat cards (each just icon+number+label) in two rows, then two empty wide cards (Active positions, KYC), then a huge empty void below. No trends, no charts, no recent-activity feed. Half the metrics are 0 because the demo backend is quiet. Redesign as a proper ops dashboard. MUST have: (a) compact top KPI row — TVL, 24h Volume, Total fees collected, Active positions — each with a tiny sparkline + delta-vs-yesterday pill (green/red). (b) two side-by-side mid-page charts — TVL over time (30d area chart) and Volume by pool (horizontal bar, top 5 pools). (c) right-rail or bottom: Recent transactions feed (last 8 swaps with timestamps, pair, amount, user, status). (d) below: Pool health table (pool, status, TVL, 24h volume, fee APR, last swap). (e) tiny services-health strip in the header showing all 8 backend microservices up/down. Russian copy throughout: «Общий TVL», «Объём 24ч», «Собрано комиссий», «Последние операции», «Пулы», «Здоровье сервисов». Sber green #21A038 as accent only, warm off-white #FAFAF8 page bg, Onest typography, 14px radius, hairline borders, tabular numerics. Density: information-rich but breathable — Bloomberg-on-a-good-day, not crypto-flashy. Render at desktop width ~1440px. Show realistic numbers (TVL like «2.4 млрд ₽» not «1 квадриллион ₽»), positive AND negative deltas so colour logic is visible.

## Assistant

_[tool: update_todos]_

I'll design this as an original ops dashboard — Sber green only as an accent, not recreating any Sber-branded UI. Let me plan, then build.

_[tool: write_file]_

_[tool: done]_

_[tool: fork_verifier_agent]_

Built the dashboard as an original ops UI — Sber green only as accent, no recreation of any Sber-branded surfaces.

**Layout (top → bottom)**
- Sidebar nav · header crumb + 8-service health strip · Refresh/Export
- **KPI row** — TVL 2.42 млрд ₽ (+1.8%), Объём 24ч 184.6 млн ₽ (−3.2%), Собрано комиссий 12.74 млн ₽ (+0.9%), Активные позиции 1 284 (−7). Each has a sparkline + delta pill (green/red logic visible on every row).
- **Charts** — TVL 30-day area chart with dashed prior-period overlay and hover tooltip · Volume by pool horizontal bars (top-5, with % share).
- **Recent operations** — 8 swaps with timestamps, pair, amount, user avatar, status (Исполнен / В обработке / Отклонён).
- **Здоровье сервисов** card — 8 microservices with latency + uptime (KYC Service flagged with warning).
- **Pool health table** — 10 of 22 pools, hairline borders, tabular nums, status dots, Δ24ч column.

Sber green #21A038 used only for accents, charts, and the brand mark. Onest type, #FAFAF8 page, 14px radius, hairline `#ECEAE3` borders throughout.

