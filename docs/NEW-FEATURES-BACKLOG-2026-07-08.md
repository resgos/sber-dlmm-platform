# New Features Backlog — 2026-07-08

**Trigger**: Sprint 8 close, audit hypothesis confirmed, platform
quality baseline solid. Time to expand the menu beyond what's already
in `MONETIZATION-STRATEGY.md` and the Sprint 9-11 plan.

**Method**: 25 features brainstormed across 5 categories. Each scored
on **revenue potential × strategic moat × Sprint-effort**. Top picks
slotted into Sprint 10-12; long-tail goes to parking lot.

> Companion to `MONETIZATION-STRATEGY.md` (the original 60+ ideas)
> and `SPRINT-PLAN.md` (the current ladder). This doc adds the
> *post-quality-baseline* batch — features that only become viable
> after Sprint 8's hardening (e.g. multi-sig needs the audit log,
> Telegram bot needs JWT revocation working).

---

## 1. Scoring rubric

- **R** (Revenue potential): 1 (cosmetic) → 5 (≥ 100M ₽/yr)
- **M** (Strategic moat): 1 (commodity) → 5 (Sber-only)
- **E** (Effort): 1 (≤ 2d) → 5 (multi-sprint)
- **Score** = R × M / E (higher = better leverage)

---

## 2. Customer-facing (retail + treasurer)

| # | Feature | R | M | E | Score | Notes |
|---|---|---|---|---|---|---|
| **F-01** | **Telegram bot notifications** — margin call, swap fill, KYC update via @SberDlmmBot. Most RU treasurers live in Telegram. | 3 | 4 | 2 | **6.0** | Notification-service already publishes events; bot is a Kafka consumer + Telegram Bot API client. Sprint 10 candidate. |
| **F-02** | **Limit orders** — "execute swap when SRUB/CNY hits X". Currently market-only. Treasurer #1 ask in 8.A pipeline interviews. | 4 | 3 | 4 | 3.0 | New scheduler + execution job + UI; bin-step semantics need design. Sprint 11. |
| **F-03** | **Recurring DCA buy** — "buy 1000 ₽ of SBER10 every Monday 09:00". Retail acquisition. | 4 | 2 | 3 | 2.7 | Quartz scheduler + user-side recurring-orders table. Sprint 12. |
| **F-04** | **Tax report export** — auto-3-НДФЛ + quarterly broker report. Real friction killer for retail. | 3 | 4 | 4 | 3.0 | Needs accountant sign-off on format. Sprint 12+ pending Compliance memo. |
| **F-05** | **Voucher / gift mode** — gift SRUB / SSPAS as one-click link (Telegram-shareable). | 2 | 3 | 2 | 3.0 | Spasibo already tokenized; gifting = transfer + notification. Sprint 10 if Spasibo write-back (8.C) lands. |
| **F-06** | **Smart hedge auto-roll** — pre-expiry, prompt user to roll the FX hedge to next month at one click. Retention. | 3 | 4 | 3 | 4.0 | Needs hedge-expiry concept (we don't have one today — Sprint 4 hedges are perpetual). Sprint 11+ once expiry model added. |
| **F-07** | **Portfolio rebalancer wizard** — "Your USDT is 60%, target was 40%, auto-rebalance for 0.3 ₽". | 3 | 2 | 2 | 3.0 | Just a UI on top of existing swap. Sprint 10 quick win. |
| **F-08** | **Backtest simulator** — "what if I'd run this strategy 90d ago" using transaction history seed. | 2 | 2 | 4 | 1.0 | Parking lot — UX retention but no direct revenue. |
| **F-09** | **In-app learn-mode tutorials** — onboarding gap. Zero learn content today. | 2 | 1 | 3 | 0.7 | Parking lot — content production is the cost. |

## 3. B2B / institutional

| # | Feature | R | M | E | Score | Notes |
|---|---|---|---|---|---|---|
| **F-10** | **Multi-sig corp wallets** — "requires 2 of 3 finance team signatures for trades >10M ₽". Corp treasury rule. | 4 | 4 | 5 | 3.2 | Big surface — wallet-rules table + approval workflow + admin UI. Sprint 11-12. Pairs naturally with audit log (Sprint 8 AU-4). |
| **F-11** | **OTC RFQ marketplace** — auction-style multi-LP quote competition (today's OTC is bilateral). | 5 | 3 | 4 | 3.75 | Builds on Sprint 9 #6.2 RFQ API. Sprint 11. |
| **F-12** | **Cross-pool routing optimizer** — best-execution across N pools. Brings high-roller volume. | 4 | 3 | 4 | 3.0 | Graph routing + execution batching. Sprint 11. |
| **F-13** | **Spread / liquidity SLA contracts** — Sber Treasury becomes guaranteed MM under monthly SLA. | 5 | 5 | 2 | **12.5** | Mostly contract paper + a config-flag MM tier already shipped (#6.4). Top pick — Sprint 10 cross-functional. |
| **F-14** | **Custom dashboards for corp B2B issuers** — branded sub-portals per issuer (R-d). | 3 | 3 | 4 | 2.25 | Multitenant theming on the B2B portal. Sprint 12. |
| **F-15** | **API key usage analytics** — per-key call volume + latency dashboards. Sprint 9 has tiers, this completes the picture. | 3 | 2 | 2 | 3.0 | Sprint 10 — natural Sprint 9 follow-up. |
| **F-16** | **DLMM-as-Service white-label deeper** — currently issuer mints tokens; extend to "design your own pool curve". | 4 | 4 | 5 | 3.2 | Q4 2026 product extension. |

## 4. Compliance / regulatory

| # | Feature | R | M | E | Score | Notes |
|---|---|---|---|---|---|---|
| **F-17** | **Real-time AML SAR auto-filing to Росфинмониторинг** — 115-ФЗ. Major moat (1 of 2 platforms in RF that would file directly). | 5 | 5 | 5 | 5.0 | Sprint 12+ — needs Compliance memo on filing format + RFM API. Score boosted because moat is huge. |
| **F-18** | **MOEX-style market surveillance** — wash-trade / layering / spoofing detection on swap flow. | 4 | 5 | 4 | 5.0 | Pure-static decision functions following AML pattern. Sprint 11. |
| **F-19** | **152-ФЗ GDPR-style "delete me" with KYC retention** — user deletion + 5y KYC retention reconciliation. | 2 | 4 | 4 | 2.0 | Compliance-driven. Sprint 12 cascade item (after 152-ФЗ audit Sprint 10). |
| **F-20** | **ESG / sustainability tokens (green pools)** — RU corporate ESG market is forming. | 3 | 3 | 4 | 2.25 | Parking — needs anchor partner (Sber GreenFinance BU). |

## 5. Operational / SRE

| # | Feature | R | M | E | Score | Notes |
|---|---|---|---|---|---|---|
| **F-21** | **Self-service KYC re-verification** — currently admin-only queue. Reduce admin queue load. | 2 | 2 | 2 | 2.0 | Sprint 10 quick win. |
| **F-22** | **Operator runbook generator** — for every Critical Prometheus alert, generate one-pager from logs + history. | 2 | 3 | 4 | 1.5 | Parking — interesting but no direct revenue. Sprint 12+. |
| **F-23** | **Chaos test schedule** — DR readiness, scheduled kill-and-restore in staging. | 3 | 2 | 3 | 2.0 | SRE-track. Sprint 11. |
| **F-24** | **Distributed tracing (Sleuth + Zipkin)** — already in RISK-REGISTER #37 (audit AU-5). | 3 | 3 | 3 | 3.0 | Sprint 10 SRE. |

## 6. Brand / ecosystem

| # | Feature | R | M | E | Score | Notes |
|---|---|---|---|---|---|---|
| **F-25** | **Sber Online integration (SberID SSO)** — single-sign-on from Сбербанк Онлайн mobile app. **Massive retail acquisition.** | 5 | 5 | 4 | **6.25** | Sber owns SberID; integration is contract+config not new auth code. Sprint 10-11 if Sber Online BU contract is willing. |
| **F-26** | **Sber DLMM SDK (JS + iOS + Android)** for partner apps to embed swap. | 4 | 4 | 5 | 3.2 | Q4 2026 extension. |
| **F-27** | **Public order book + market-data API** for analysts, makes us the de-facto RF DLMM data source. | 3 | 3 | 3 | 3.0 | Sprint 11. |
| **F-28** | **Educational content + tutorials** — onboarding tutorial flow. | 2 | 1 | 4 | 0.5 | Parking — content cost is the issue. |

---

## 7. Top picks ranked

1. **F-13 SLA MM contracts** (score 12.5) — highest leverage, Sprint 10 PO trek.
2. **F-25 Sber Online SSO** (score 6.25) — retail acquisition lever, Sprint 10-11.
3. **F-01 Telegram bot** (score 6.0) — RU treasurer expectation, Sprint 10 code.
4. **F-17 AML SAR auto-file** (score 5.0) — regulatory moat, Sprint 12.
5. **F-18 Market surveillance** (score 5.0) — same compliance bundle, Sprint 11.
6. **F-06 Smart hedge auto-roll** (score 4.0) — retention play, Sprint 11.
7. **F-11 OTC RFQ marketplace** (score 3.75) — Sprint 9 #6.2 follow-up, Sprint 11.

## 8. Suggested Sprint 10-12 layout

**Sprint 10 — "Commercial expansion + EN i18n + workspaces refactor"**:
- F-13 SLA MM contract paper (PO + Legal)
- F-25 Sber Online SSO design + Sber Online BU contract
- F-01 Telegram bot MVP (notification-service consumer)
- F-07 Portfolio rebalancer wizard (UI on top of swap)
- F-15 API key analytics dashboard
- F-21 Self-service KYC re-verification
- Carry: admin-bff WebClient finish, EN translation, workspaces

**Sprint 11 — "Routing + MM + Surveillance"**:
- F-11 OTC RFQ marketplace
- F-12 Cross-pool routing optimizer
- F-06 Hedge auto-roll (needs expiry model first)
- F-18 Market surveillance (wash-trade / layering)
- F-23 Chaos test schedule
- Carry: index funds (slid from current plan)

**Sprint 12 — "Compliance battery + AML SAR + tax export"**:
- F-17 Real-time AML SAR auto-filing
- F-04 Tax report export
- F-10 Multi-sig corp wallets (kicks off; multi-sprint)
- F-14 Custom B2B dashboards
- F-19 152-ФЗ delete-me reconciliation

---

## 9. Revenue impact projection (updated)

| Quarter | Run-rate at end | Vs Sprint 8 forecast |
|---|---|---|
| Q3 2026 (Sprint 9 close) | **800M-1.1B ₽/yr** | unchanged (commercial sprint executes per plan) |
| Q4 2026 (Sprint 10-11) | **1.5-2.0B ₽/yr** | ↑ vs Sprint 8 1.3-1.8B forecast (SLA contracts + SberID drive uplift) |
| Q1 2027 (Sprint 12) | **2.2-2.8B ₽/yr** | ↑ vs Sprint 8 1.7-2.3B (AML moat + multi-sig + tax export close enterprise gaps) |
| Q2 2027+ | **3.5-4.5B ₽/yr** | ↑ vs Sprint 8 3-4B (tokenized bonds + DLMM SDK + governmental contracts) |

The **F-13 SLA contracts** alone could move Q4 from 1.5 → 1.8B if 3
contracts sign at 50M ₽/yr each. The **F-25 SberID integration** is
the multiplier — every Sber Online user becomes a single-tap signup;
even 0.1% conversion of 100M Sber Online users → 100K new DLMM users.

---

## 10. What we won't build (explicit parking)

| Feature | Why parked | Re-evaluation trigger |
|---|---|---|
| F-08 Backtest simulator | UX-only retention, no direct revenue | If MAU growth < 5% q-o-q |
| F-09 In-app learn-mode | Content production cost is the bottleneck | When marketing budget freed |
| F-20 ESG tokens | Needs Sber GreenFinance BU anchor | When BU signals interest |
| F-22 Runbook generator | Cool, no revenue | After 5+ Critical alerts a quarter |
| F-26 SDK for partners | Q4 2026+ extension | When B2B portal hits 10+ live issuers |
| F-28 Tutorials | Content cost | Same as F-09 |

---

*Author: PO + IT-lead + Marketing. Companion to `MONETIZATION-STRATEGY.md`
(60-idea original) + `SPRINT-PLAN.md` (current ladder). Updated at
quarter close.*
