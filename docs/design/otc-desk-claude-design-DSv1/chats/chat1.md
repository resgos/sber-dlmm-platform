# OTC Desk Redesign

_Started 2026-05-20 09:45 UTC_

---

## User

Redesign the admin-ui OTC desk page (route /otc). This is institutional block-trade workflow that sits beside the AMM pool routing. State machine: REQUESTED → QUOTED → ACCEPTED → SETTLED (or REJECTED / EXPIRED). Audience: ops desk operator routing big-ticket OTC trades between institutional counterparties. Currently the page is a near-empty AntD Table with status filter + 'New deal' CTA — no funnel view, no quote-vs-mid spread visualisation, no live RFQ inbox.

## Assistant

_[tool: list_files]_

_[tool: list_files]_

_[tool: list_files]_

_[tool: list_files]_

_[tool: grep]_

_[tool: read_file]_

_[tool: read_file]_

_[tool: read_file]_

_[tool: list_files]_

_[tool: read_file]_

_[tool: read_file]_

_[tool: read_file]_

_[tool: list_files]_

_[tool: read_file]_

_[tool: grep]_

_[tool: grep]_

_[tool: list_files]_

_[tool: update_todos]_

_[tool: copy_files]_

_[tool: write_file]_

Now let me build the OTC page. I'll write a small extension CSS, then a structured JSX file.

_[tool: write_file]_

Now the main JSX with data and components:

_[tool: write_file]_

_[tool: show_html]_

_[tool: done]_

_[tool: fork_verifier_agent]_

Built the OTC desk redesign at **OTC Desk.html** (1440px). What's in it:

- **Sidebar + topbar** matching admin-ui (Sber green only on the active OTC item, live-gateway pill, ops-desk user chip)
- **KPI row** — Ожидают котировки · Котировок сегодня · Объём расчётов 24ч · Средний спред к mid (all flat cards, tabular nums, sparkline only on volume)
- **Two-pane main:**
  - **Left — RFQ-инбокс**: stage filter chips + sectioned table grouped by «Ожидает котировки / Котировка выставлена / Принята к расчёту», each section with count + total volume. Per-row: counterparty, side+pair, size in base + ₽ equiv, mid, our quote with spread-bps pill, live countdown bar.
  - **Right — deal detail**: status tag, counterparty card (Инициатор → Маркет-мейкер), request/mid/quote triplet, **spread-vs-mid track viz** with mid tick and quote marker on a ±15 bps axis, expiry countdown (color-shifts when <2 min), audit trail (history with done/current/pending dots), stage-aware action buttons.
- **Bottom strip** — last 8 settled deals on a horizontal timeline, each with pair, size, counterparty, P&L (₽ + bps), running total at top-right.
- **Live tick** — REQUESTED/QUOTED countdowns decrement every second.

Caveats / next steps:
- Russian institutional names are plausible mock data, not real desks — easy to swap.
- The "New deal" / "Quote entry" / "Edit quote" flows are buttons only; modal forms not built unless you want them.
- Detail panel updates by clicking any row — try the urgent ETH RFQ (#0479, ~1:26 left) or any QUOTED row to see the spread viz.

## User

Verifier agent check completed

