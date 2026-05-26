# Business Improvements Plan — Revenue/Retention/Expansion (2026-05-26)

> Tactical business доработки на Q3-Q4 2026 + Sprint 16+.
> Что отгружает измеримую бизнес-ценность: revenue вверх, churn вниз,
> рынок шире. Каждый item — concrete deliverable не "идея".
>
> Это НЕ:
> - Стратегия (см. `docs/MONETIZATION-STRATEGY.md`)
> - Технический roadmap (см. `docs/ROADMAP-LONGTERM-2026-05-25.md`)
> - Testing investments (см. `docs/TESTING-INVESTMENT-PLAN-2026-05-26.md`)
>
> Это **execution backlog** — что product owner отдаёт engineering
> чтобы за квартал двигать topline.

---

## 0. Категории и приоритеты

| Tier | Bias | Когда | Total effort |
|------|------|-------|--------------|
| **T1 — Revenue NOW** | Прямая денежная ценность на пилоте | Sprint 13-14 | ~15d |
| **T2 — Retention** | Защита от churn пилотных клиентов | Sprint 14-15 | ~14d |
| **T3 — Market expansion** | Открыть новые сегменты | Sprint 15-16 | ~20d |
| **T4 — Trust + ops** | Compliance / scale-out enablers | Sprint 16+ | ~17d |
| **Quick wins** | Каждое < 1 day | сразу можно | 5d total |

---

## 1. Tier 1 — Revenue NOW (Q3 2026)

### B-01 — Tier upsell CTA + payment integration
**Что:** F-15 analytics показывает throttle ratio per tier. Добавить:
- В user-ui после первого 429 в Q-сессии — modal "Достигнут лимит FREE (10 RPS). Upgrade на PRO (100 RPS) — 30 000 ₽/мес"
- В Profile → Тариф — card с текущим tier + button "Сменить"
- Backend: `POST /api/v1/billing/upgrade` создаёт invoice через Sber Платежи или integrates с подписочной системой

**Effort:** L (5d) — нужен billing integration
**Owner:** BE + FE + Billing integration team
**Business case:** FREE → PRO conversion @ 5% × 100 pilot users × 30k = +1.5M ₽/мес. Сейчас 0 conversion (нет CTA, нет flow).
**Blocker:** контракт с Sber Платежи на subscription billing
**ROI:** 6 месяцев payback

---

### B-02 — Volume rebate auto-apply
**Что:** Treasury клиенты с volume > 10M ₽/day автоматически получают 10% fee discount. Видно в bill:
- Add `volume_discount_pct` column to `users` table
- Daily scheduled job (Sprint 11 G-16 pattern): пересчитывает 30-day rolling volume per user, обновляет discount tier
- `FeeCalculator` в pool-engine читает user's tier при swap
- UI: "Volume discount −10% активен" badge на Dashboard

**Effort:** M (3d)
**Owner:** BE + FE
**Business case:** Удерживает high-volume users от уходf на конкурента / OTC desk. Defence-mode investment.
**ROI:** Нельзя посчитать в isolation; в comparable products retention +15% для top-decile users

---

### B-03 — Premium analytics paywall (repackage G-23)
**Что:** Pro-metrics (Sharpe, MaxDD, 30d vol) — мы только что отгрузили в G-23 backend free для всех. Repackage:
- FREE tier: только TVL, volume, APY
- PRO tier: + 30d volatility, MaxDD
- ENTERPRISE: + Sharpe, custom rollups, CSV export
- FE: показать "🔒 Доступно с PRO" заглушку для FREE users + upgrade CTA

**Effort:** S (1.5d)
**Owner:** FE + small BE for tier gating
**Business case:** Аналитика — самый частый "wow"-момент в демо. Прямой driver для PRO upsell.
**Risk:** Pilot users уже видят их free → возможен backlash. Решение: grandfather existing pilot orgs на ENTERPRISE-level features до конца pilot quarter.

---

### B-04 — Customer Success weekly metrics export
**Что:** Automated weekly CSV → PO email:
- Active orgs (last 7d login)
- MRR per org (sum of fees × revenue share)
- Churn warning: orgs that haven't logged in > 7d but had transactions before
- Top-5 fee-yielding orgs
- 2FA adoption %, KYC verified %

**Effort:** S (1.5d)
**Owner:** BE (admin-bff endpoint + cron schedule + mail)
**Business case:** PO сейчас вручную тянет цифры из админки → 2-3 часа в неделю. Автоматизировать → PO больше времени на customer calls.

---

### B-05 — ROI calculator landing page (public)
**Что:** Public URL `/calculator` (без auth) — landing с интерактивным:
- Input: "ваш treasury portfolio: SBER X ₽, валютные Y ₽, временной горизонт Z мес"
- Output: estimated fee yield + comparison table "DLMM vs traditional MM vs OTC desk"
- CTA: "Запросить демо"
- No mock data — реальные cohort averages из нашей `liquidity_pools` aggregated

**Effort:** M (3d) — FE form + lead capture form + backend для real numbers
**Owner:** FE + Marketing copy + 0.5 BE
**Business case:** Lead-gen для outbound sales. Industry-standard для DeFi/CeFi products.

---

**Tier 1 total: 14 days. Output: paying revenue funnel + retention defence + lead-gen.**

---

## 2. Tier 2 — Retention (Q4 2026)

### B-06 — Pilot health dashboard (internal PO tool)
**Что:** New page `/admin/pilots`:
- Per-org engagement score (composite: logins + actions + position-count + fee-revenue)
- NPS survey trigger (in-app modal monthly: "Recommend us?")
- Support tickets count (manual or Jira integration)
- "At-risk" flag (score < threshold for 2+ weeks)
- Action buttons: "Schedule check-in call", "Send re-engagement email"

**Effort:** M (4d)
**Owner:** FE + BE small
**Business case:** Identify churn 30 дней до факта. Saves 1 pilot worth ~3-5M ₽/yr revenue per save.

---

### B-07 — Multi-channel notification delivery
**Что:** Notification-service сейчас только записывает в `notifications` table. Добавить:
- Email channel (SMTP / Sber Mail) для critical events (margin call, KYC update, large swap)
- Slack/Teams webhook channel для org admins
- In-app push (browser Notifications API) для users sitting на странице
- User preferences: per-channel toggle в Profile → Уведомления

**Effort:** M (4d)
**Owner:** BE + small FE
**Business case:** "Margin call" в email → trader среагирует за 5 мин vs "in-app only" → среагирует когда логин (часы). Reduces P0 escalations.

---

### B-08 — Re-engagement email automation
**Что:** Scheduled job (daily):
- Find users not logged in for ≥ 7 дней
- Calc их unclaimed fees / out-of-range positions
- Send email "У вас 1234 ₽ непринятых комиссий и 2 позиции out-of-range"
- Track click-through, log в `re_engagement_log`

**Effort:** S (1.5d) — extends B-07's email infra
**Owner:** BE
**Business case:** Industry benchmarks: 15-25% reactivation rate from such emails. На 100 пилотных = +15-25 active users monthly.

---

### B-09 — Org admin self-serve portal
**Что:** New section `/team/admin` (для OWNER роли):
- View their org's TVL, volume, fees collected (last 30d, last quarter)
- Audit log self-serve filter + CSV export ("показать все действия Иванова за месяц")
- Members management (already есть в G-21)
- Billing history (когда B-01 landed)
- API token management (rotate, revoke, scope)

**Effort:** M (3d)
**Owner:** FE + BE for API tokens
**Business case:** Enterprise checklist item. Без этого продаваться enterprise не сможем — IT-Sec требует "audit log self-serve".

---

### B-10 — Compliance self-serve forensic
**Что:** Endpoint `GET /api/v1/admin/forensic/transaction/{txId}` — single transaction full lineage:
- Initial request payload
- Auth context (user, org, role, IP, JWT claims)
- Each step: quote → validate → balance deduct → execute → settlement → audit log
- Each downstream call (token-service, fee-service responses)
- Final state diff (balance before / after)
- Returns PDF or JSON suitable to forward to compliance / regulator

**Effort:** M (3d) — combines existing audit_log + outbox_events + transaction-service data
**Owner:** BE (admin-bff)
**Business case:** "Регулятор спросил про Tx X" — выдать за 5 мин не за 5 дней. Pre-empts 115-ФЗ audit pain.

---

**Tier 2 total: 15.5 days. Output: 30-day churn early-warning + enterprise self-serve + compliance forensic.**

---

## 3. Tier 3 — Market expansion (Sprint 15-16)

### B-11 — B2B issuer self-serve onboarding
**Что:** Currently issuer creates token + pool through admin (manual). Self-serve flow:
- `/issuer/onboard` page (no auth → email signup → KYB upload)
- After KYB approved: token-config wizard (symbol, supply, decimals, mintable yes/no)
- Pool-create wizard (pair selection, bin step, fee, initial liquidity)
- Auto-generates contract docs PDF (via DocuSign API or local template)
- Self-serve activation после подписи

**Effort:** L (8d) — KYB integration + wizard + PDF gen
**Owner:** BE + FE + Compliance
**Business case:** Unlocks "long-tail" issuer market (50-100 small issuers vs 5-10 big ones). Each $5-20M $/yr revenue. ~+50M ₽/yr potential.
**Risk:** KYB false-positives, contract template legal review

---

### B-12 — EN i18n complete sweep
**Что:** NEW-3 уже отгрузил 2 страницы (PoolsPage + PoolDetailPage). Доделать оставшиеся 10:
- PositionsPage, DashboardPage, ProfilePage, TransactionsPage
- HedgePage, TeamPage, RebalancePage, ReviewsPage
- LoginPage / RegisterPage, PoolComparePage
- Language switcher fully wired (currently localStorage flag)
- EN content reviewed by financial-domain translator (not Google Translate)

**Effort:** M (3d FE + 1d translation review)
**Owner:** FE + external translator
**Business case:** Opens до foreign export customers (Sber Treasury foreign branches, foreign counterparties). Q4 enterprise pipeline blocker.

---

### B-13 — Public webhooks API
**Что:** Allow institutional users to subscribe to events programmatically:
- `POST /api/v1/webhooks` — register URL + secret + event types
- Events: `position.opened`, `position.closed`, `fees.accrued`, `swap.executed`, `margin.warning`
- HMAC signature on payload
- Retry with exponential backoff + dead-letter
- Admin UI: `/admin/webhooks` to view recent deliveries + failures

**Effort:** M (3d)
**Owner:** BE
**Business case:** Enterprise blocker — institutional clients integrate via webhook, not polling. Sales говорил это первый вопрос Sber Treasury.

---

### B-14 — Multi-currency display
**Что:** TVL / volume / balances везде в RUB сейчас. Добавить:
- Profile → Settings → "Display currency: RUB / USD / EUR / CNY"
- All ₽ values rendered through `formatCurrency(value, userLocale.currency)`
- Uses `dlmm-price-oracle` rates for live FX (already есть oracle service)
- Backend stays RUB-only (single source of truth)

**Effort:** S (2d) — pure FE + rate lookup
**Owner:** FE
**Business case:** Export blocker — foreign clients want USD or CNY for reporting/comparison.

---

### B-15 — Sber Online deep link integration
**Что:** Retail-side integration:
- Sber Online app generates deep link `sberdlmm://swap?from=SRUB&to=SBER&amount=10000`
- Our user-ui handles `?from=&to=&amount=` query params → opens /swap pre-filled
- Add "Pay with Sber Online" button — initiates Sber ID OAuth flow → deep-link back с auth code
- Track conversion: deep-link clicks → completed swaps

**Effort:** M (3d)
**Owner:** BE for OAuth + FE for handler
**Blocker:** Sber Online BU contract (parked в roadmap as F-25, contract-blocked)

---

**Tier 3 total: 19 days. Output: self-serve B2B + EN + webhooks + multi-currency + Sber Online.**

---

## 4. Tier 4 — Trust + ops scale (Sprint 16+)

### B-16 — Real-time compliance dashboard
**Что:** Admin page `/admin/compliance`:
- Today's stats: KYC reviews count, SAR triggered, sanctions hits
- Pending: KYC queue с aging, suspicious tx queue
- Trends: 30-day chart per category
- Alerts: SLA breaches (KYC > 24h pending)

**Effort:** M (3d)
**Owner:** FE + admin-bff queries
**Business case:** Compliance team productivity + ЦБ-readiness ("we have a compliance dashboard").

---

### B-17 — GDPR-style data request handler
**Что:** Endpoints:
- `GET /api/v1/users/me/data-export` — generates ZIP with all user's data (profile, positions, transactions, audit log, notifications)
- `POST /api/v1/users/me/data-deletion` — initiates deletion with 30-day cool-down period + admin approval
- Audit log entries за каждый request

**Effort:** M (3d)
**Owner:** BE
**Business case:** 152-ФЗ требование. Avoids Роскомнадзор fine + supports B2C expansion.

---

### B-18 — Bulk admin operations
**Что:** Admin UI extensions:
- /admin/users — multi-select → bulk: block / unblock / change tier / send broadcast email
- /admin/transactions — multi-select → bulk mark reviewed
- /admin/pools — multi-select → bulk pause / resume
- Audit log captures every bulk action with target IDs

**Effort:** M (3d)
**Owner:** FE + small BE for bulk endpoints
**Business case:** Admin productivity at scale (when pilot грows to 500+ users). Without bulk → admin spends day clicking individual rows.

---

### B-19 — Saved searches + per-user filters
**Что:** Every table (positions, transactions, users) → "Save current filter as named view". Persist в user prefs.
- Quick-switch dropdown: "All transactions / My team only / Suspicious / Last 7 days"
- Default view per user

**Effort:** S (1.5d)
**Owner:** FE only (uses existing query params)
**Business case:** Daily-driver UX win. Treasury staff returns to 2-3 filtered views every login.

---

### B-20 — Self-serve sandbox environment
**Что:** Public URL `sandbox.sber-dlmm.com`:
- Pre-loaded with synthetic Org, 5 demo users (treasurer/risk/admin/junior/observer)
- All data wipes every hour (cron `down -v && up -d`)
- "Try it" button on landing page → instant sandbox login
- Banner "🧪 SANDBOX — all data resets hourly"

**Effort:** M (3d) — separate compose + cron + small landing page
**Owner:** SRE + FE landing
**Business case:** Sales enablement. Prospects test без NDA-protected demo call. Saves 30+ sales hours / month.

---

**Tier 4 total: 13.5 days. Output: compliance UI + GDPR endpoint + admin scale tools + sandbox.**

---

## 5. Quick wins (each < 1 day, можно начать сразу)

| ID | Item | Effort | Why now |
|----|------|--------|---------|
| **QW-1** | "Download CSV" button to every table (positions, transactions, users, audit log) | S | Уже все query endpoints returnят данные; нужен только button + CSV формат конверсия |
| **QW-2** | Static `/pricing` page — FREE/PRO/ENTERPRISE comparison table, no payment yet | S | Marketing блокирован отсутствием public pricing |
| **QW-3** | Personalized demo URL — `?company=AlphaTreasury` shows "Hi, AlphaTreasury demo" в header | S | Sales рабочий tool, 1 hour |
| **QW-4** | Notes/tags field на pools + users + transactions (free-text 200 chars + admin only) | S | Compliance team просит — "пометить эту tx подозрительной" |
| **QW-5** | Activity log per entity (uses existing admin_audit_log) — sidebar on pool/user/tx detail | S | All data есть, нужен только UI |

**Quick wins total: ~5 days. Output: visible UX wins + sales enablement.**

---

## 6. Revenue projection (cumulative if all delivered)

| Quarter | Items shipped | Estimated revenue add |
|---------|---------------|----------------------|
| Q3 2026 (Sprint 13-14) | Tier 1 + B-06/B-07 | +2.5M ₽/мес recurring (Tier upsell, retention) |
| Q4 2026 (Sprint 14-15) | Tier 2 + B-12 EN | +1.5M ₽/мес (churn reduction, EN market access) |
| Q1 2027 (Sprint 15-16) | Tier 3 | +5M ₽/мес (B2B issuer self-serve unlocks long-tail) |
| Q2 2027+ | Tier 4 | Compliance / scale-out (no direct revenue, but enables 50M ₽ enterprise contracts) |

**Cumulative end-2026 run-rate from this plan alone: +50M ₽/yr.**

(Original revenue forecast в `MONETIZATION-STRATEGY.md` стоит на 800M-1.1B Q3, 1.5-2B Q4. This plan делает execution, не stratégie.)

---

## 7. Sequencing recommendation (что брать первым)

**Если есть только 1 sprint:** Tier 1 целиком (B-01..B-05) — прямая денежная ценность.

**Если есть 2 sprint'a:** Tier 1 + (B-06 pilot health dashboard + B-07 notifications). Защита от churn до ship новых features.

**Если есть quarter:** Tier 1 + Tier 2 + B-12 EN + quick wins. Full revenue + retention package.

**Если quarter + 1:** + Tier 3 expansion. B-11 issuer self-serve — биггест unlock.

**Tier 4 — enterprise/scale items, defer до уверенного product-market fit (after 30+ pilot orgs).**

---

## 8. Resource allocation (rough)

| Team | Tier 1 | Tier 2 | Tier 3 | Tier 4 | Total (3 quarters) |
|------|--------|--------|--------|--------|--------------------|
| **BE** | 8d | 9d | 11d | 7d | 35d (~1.75 FTE for 4w each) |
| **FE** | 5d | 5d | 7d | 5d | 22d (~1 FTE for 4w + buffer) |
| **Billing integration** | 2d | 0 | 0 | 0 | 2d (external dep на Sber Платежи) |
| **Compliance** | 0 | 2d | 2d | 1d | 5d |
| **Marketing copy** | 1d | 0 | 0.5d | 0 | 1.5d |
| **Translator** | 0 | 0 | 1d | 0 | 1d |

**Bottleneck:** Backend Sprint 13+14+15 — нагрузка peak. Mitigate: parallelize Tier 3 items в Sprint 16 (defer).

---

## 9. Decision points (need PO input)

1. **Tier 1 first vs portfolio?** Mы рекомендуем Tier 1 целиком в Sprint 13-14. Альтернатива — параллельно стартовать B-11 issuer self-serve (highest upside но длиннее ship time).
2. **B-03 premium analytics paywall — grandfather pilot users или нет?** Recommendation: grandfather to end-of-Q3, then move to standard tiers. Сейчас pilot ожидает full access.
3. **B-15 Sber Online deep link — wait for contract или start scaffolding?** Recommendation: scaffold the deep-link handler сейчас (3 hours work), full activation после contract signed.
4. **QW-2 public pricing page — что there put?** PO решает FREE: 0₽ / PRO: ?₽/мес / ENTERPRISE: contact us. Без этого markup conversation с пилотами скользкая.
5. **B-20 sandbox env — отдельный домен или подпуть?** sandbox.sber-dlmm.com clean но требует DNS + LE cert. /demo на main domain быстрее но мешает SEO/analytics.

---

## 10. Что НЕ в этом плане (explicit out-of-scope)

| Item | Why excluded |
|------|--------------|
| Mobile app (iOS/Android) | Sber Online уже есть mobile presence; B-15 deep link даёт нам mobile reach |
| Public DEX listing | Not strategic; we're institutional liquidity platform |
| Telegram bot | User explicitly excluded в Batch #2 |
| Cross-chain bridge | Sber doesn't operate cross-chain (parking lot в roadmap) |
| Governance token / DAO | Not commercially needed (parking lot) |
| White-label licensing | Sprint 20+, requires multi-tenancy refactor |
| Mining/staking products | Out of treasury MM scope |
| NFT receipts for LP | Cosmetic, no PO demand (parking lot) |

---

## 11. Tagging convention для tracker

When tickets land:
- `business-improvement` — this plan's B-* / QW-* items
- `revenue` (Tier 1)
- `retention` (Tier 2)
- `expansion` (Tier 3)
- `trust-ops` (Tier 4)
- `quick-win` (QW-*)
- `pilot-blocker` — must ship before next pilot client
- `enterprise-blocker` — must ship для first 50M₽ enterprise contract

---

*Author: Engineering · 2026-05-26. Companion to MONETIZATION-STRATEGY.md (strategy), TESTING-INVESTMENT-PLAN-2026-05-26.md (testing), ROADMAP-LONGTERM-2026-05-25.md (technical). Next review: end of Sprint 13.*
