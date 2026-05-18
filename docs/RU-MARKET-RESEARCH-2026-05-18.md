# RU-Market Feature Research — BA Discovery Session

**Date**: 2026-05-18, immediately after Sprint 4 acceptance.
**Format**: 2-hour structured brainstorm + filter session.
**Owner**: BA (lead) + 2nd BA (junior, scribe).
**Participants**: BA lead, junior BA, SA (technical sanity), PO (commercial filter), Compliance lead (regulatory veto), IT-lead (notes).

> **Motivation**: Sprint 4 acceptance closed the foundational layer. Sprint 5+
> revenue ramp depends on Russian-market fit, not just generic DLMM features.
> CFO + Treasury BU pushback: "what makes us **Russian** beyond the SRUB ticker
> and Cyrillic UI?" This memo answers that.

---

## 1. Method

For each candidate feature, score on:

| Axis | 1 | 3 | 5 |
|---|---|---|---|
| **Regulatory necessity** | nice-to-have | recommended | mandatory for production |
| **Revenue link** | indirect | enables a pilot | direct revenue stream |
| **Pilot readiness** | needs 3+ external parties | needs 1 external party | doable internally |
| **Effort (inverted)** | 3+ sprints | 1 sprint | ≤1 week |

Sum ≥ 14 → **Tier 1** (Sprint 5/6 candidate).
Sum 10–13 → **Tier 2** (Sprint 7+ track or design memo now).
Sum < 10 → **Parking lot** with re-evaluation trigger.

---

## 2. Brainstorm — full candidate list (no filter yet)

Categories pulled from Russian financial market reality, not generic
"emerging market" thinking. Each category has 2-4 concrete features.

### 2.1 Регуляторика (regulatory compliance, must-haves)

| # | Feature | Why now |
|---|---|---|
| RU-R1 | **152-ФЗ compliance audit** — data residency, encryption-at-rest verification, ПДн journal | DLMM stores ИНН + sbbol_account_id (#4.5) → falls under ПДн. Need formal audit before Treasury onboards. |
| RU-R2 | **115-ФЗ AML — Росфинмониторинг integration** | Any transaction >600k ₽ is reportable. Currently we log to `transactions` table but don't push to Rosfinmonitoring's `Личный кабинет` |
| RU-R3 | **ЦБ РФ Реестр финансовых платформ — application + listing** | Without this we can't legally serve B2B settlement to entities not pre-registered. Treasury LP onboarding (4.A) implicitly requires this. |
| RU-R4 | **161-ФЗ payment-instrument compliance** for SBP B2B rail | If we route SBP we're a payment operator → 161-ФЗ kicks in |
| RU-R5 | **259-ФЗ ЦФА classification** — are DLMM tokens "цифровые финансовые активы"? | If yes, Atomyze/Masterchain/Sber DFA platform registration applies. If no, we're in безопасный "internal balance" zone but lose the ЦФА monetization vector |
| RU-R6 | **Самозапрет на финансовые продукты** (115-ФЗ amendment 2024) | Mandatory for retail since 2024-09. User can self-restrict from new positions; immutable record; ЦБ РФ верификация |

### 2.2 Платёжные интеграции

| # | Feature | Why |
|---|---|---|
| RU-P1 | **СБП B2B-рельс для outbound RUB** | #4.6 prototype only moves DLMM-internal balances. Real corp client expects RUB to leave to their actual bank account via SBP — that's the only Russian instant interbank rail |
| RU-P2 | **СПФС (SWIFT alt) routing для cross-border BRICS** | CNY corp settlement to Chinese counterparty: SWIFT closed for many SBER routes since 2022. СПФС is the alternative |
| RU-P3 | **Платёжная система Мир — карточный binding для retail** | Lower priority for B2B DLMM, but retail user UX requires it long-term |
| RU-P4 | **SberPay widget integration** | One-click fund-DLMM-balance for Sber retail clients |

### 2.3 Идентификация

| # | Feature | Why |
|---|---|---|
| RU-I1 | **ЕСИА (Госуслуги) OIDC handoff** | Alternative to SBBOL (#4.5) for retail or non-SBBOL corp clients. Госуслуги = 100M+ verified citizens |
| RU-I2 | **Sber ID universal SSO** | Different from SBBOL — Sber ID is для retail across Sber ecosystem (SberMegaMarket, SberZdorovye, etc.). Reuses KYC. |
| RU-I3 | **ЕБС (Единая биометрическая система)** | Биометрический KYC. Required for fully-remote onboarding of new B2B clients without physical visit. Expensive integration (~3-6 months) |
| RU-I4 | **Госключ (мобильная ЭП через приложение)** | Free electronic signature for citizens; future-replacement of physical signed B2B settlement mandate (§4.2 of SBBOL-INTEGRATION-DESIGN.md) for amounts < 5M ₽ |

### 2.4 Налоговый учёт

| # | Feature | Why |
|---|---|---|
| RU-T1 | **НДФЛ для физлиц — годовая 6-НДФЛ справка** | Retail user has trading gains → required to report. Generate XML in ФНС-acceptable format, downloadable from профиль. |
| RU-T2 | **НДС-учёт для B2B operations** | Each B2B fee (custody, settlement, listing) → надо включать НДС 20% или льготная ставка. Currently NOT split. |
| RU-T3 | **1С банк-клиент XML экспорт** | Extension of #4.4 CSV report into 1С банк-клиент v3.0 XML format. 90%+ корпоративный бухгалтер сидит в 1С. |
| RU-T4 | **ФНС API auto-reporting** | Automated annual tax filing for individual users (opt-in). Big UX win, big regulatory lift. |

### 2.5 Market data

| # | Feature | Why |
|---|---|---|
| RU-M1 | **ЦБ РФ official rates feed (RUB-USD/EUR/CNY daily)** | Free public API. Demo-day win: "show DLMM market rate vs ЦБ official rate spread" — treasurers immediately see arbitrage opportunity (or efficiency story) |
| RU-M2 | **MOEX ISS real feed (already in Sprint 4 #4.F)** | Already tracked. Sprint 6 target. |
| RU-M3 | **БРИКС Pay rail integration** | Emerging cross-border payment rail. Speculative — actual API access uncertain in 2026 |

### 2.6 Документооборот + операции

| # | Feature | Why |
|---|---|---|
| RU-D1 | **Российский банковский календарь** | Custody fee accrual (#3.3) and margin-call timing (#4.3) currently don't know about RU holidays. Settlement display "T+2 рабочих дня" needs to skip holidays |
| RU-D2 | **ЭДО (электронный документооборот) integration** | СБИС / Контур.Диадок / Тензор интеграция для B2B документов. Audit trail bridges into corp's existing system |
| RU-D3 | **Эквайринг через банк** | Corp merchant payment processing in DLMM context — speculative until B2B portal demonstrates demand |

### 2.7 ЦФА (Цифровые финансовые активы) интеграция

| # | Feature | Why |
|---|---|---|
| RU-C1 | **ЦФА классификация DLMM-токенов — design memo** | Decide: are SBER/GAZP/etc. DLMM tokens ЦФА under 259-ФЗ? Determines whether we need to register with ЦБ как оператор ЦФА (huge lift) or stay in "internal clearing" reg-frame (#3.A again) |
| RU-C2 | **Атомайз / Мастерчейн listing discovery** | Both run ЦФА issuance platforms. DLMM could potentially list THEIR-issued ЦФА as tradeable assets — open new revenue line (ЦФА secondary market) |
| RU-C3 | **Sber DFA platform integration** | Sber's own internal ЦФА platform. Если DLMM становится secondary market для Sber DFA-issued tokens — strategic alignment + потенциал внутренней маркетинговой синергии |

### 2.8 Sber-specific (помимо SBBOL/Spasibo)

| # | Feature | Why |
|---|---|---|
| RU-S1 | **SBBOL OIDC handoff** (уже как 5.B в backlog) | — |
| RU-S2 | **SberSpasibo conversion** (уже Sprint 5) | — |
| RU-S3 | **СберБизнес Эквайринг bridge** | Корп клиент принимает платежи от своих клиентов через Эквайринг, settlement в DLMM-баланс автоматом |
| RU-S4 | **Sber CRM / SberCo data sharing** для KYB | Inherit KYB from SberBusiness — like SBBOL but data-only, no auth |

### 2.9 Защита пользователя

| # | Feature | Why |
|---|---|---|
| RU-U1 | **Самозапрет (RU-R6, дубль из регуляторики)** | — |
| RU-U2 | **Период охлаждения для крупных операций (RU 2024 law)** | Для физлица: операция > X RUB → 24-48h задержка с возможностью отмены, защита от мошенничества. Не уверены применимо ли к нашим B2B-сценариям |
| RU-U3 | **AML alert для подозрительных паттернов** (повторяющиеся round-amount, fast-in-fast-out) | Дополнение к RU-R2 — proactive detection до того как clean stuff попадёт в reportable Росфинмониторинг feed |

### 2.10 Импортозамещение

| # | Feature | Why |
|---|---|---|
| RU-X1 | **Redis → Apache Ignite (или KeyDB)** | См. отдельный memo по запросу IT-lead. Backlogged. |
| RU-X2 | **PostgreSQL → Pangolin / Postgres Pro** | Pangolin/PgPro — российские форки Postgres из реестра Минцифры. Drop-in compatible. |
| RU-X3 | **Kafka → Apache Kafka на сборках Russian Vendor** или Yandex Message Queue | Apache Kafka сам по себе Apache 2.0, импортозамещение не нужно. Можно остаться. |
| RU-X4 | **Реестр Минцифры — включение DLMM platform** | Регистрация платформы в реестре отечественного ПО. Открывает возможность контрактов с госорганами + налоговые льготы (отмена НДС на ПО). PO + Legal трек. |

---

## 3. Filter — scoring matrix (BA + SA + PO + Compliance vote)

Each cell averaged across 4 voters (1-5 scale).

| # | Feature | Reg | Rev | Pilot | Effort(inv) | **Sum** | Tier |
|---|---|---|---|---|---|---|---|
| **RU-R1** | 152-ФЗ audit | 5 | 1 | 3 | 3 | **12** | T2 (Sprint 7 must-finish) |
| **RU-R2** | 115-ФЗ Росфинмониторинг feed | 5 | 1 | 3 | 2 | **11** | T2 (Sprint 7) |
| **RU-R3** | ЦБ Реестр финплатформ application | 5 | 3 | 1 | 1 | **10** | T2/T3 (multi-quarter, PO trek) |
| **RU-R4** | 161-ФЗ payment-instrument compliance | 5 | 1 | 1 | 1 | **8** | T3 — gated by SBP rail decision |
| **RU-R5** | 259-ФЗ ЦФА классификация (memo) | 5 | 3 | 5 | 4 | **17** | **T1 — Sprint 5 SA memo** |
| **RU-R6** | Самозапрет 115-ФЗ | 5 | 1 | 5 | 4 | **15** | **T1 — Sprint 6** |
| **RU-P1** | СБП B2B outbound rail | 4 | 5 | 1 | 1 | **11** | T2 — Sprint 6+ if RU-R4 unlocks |
| **RU-P2** | СПФС routing | 3 | 5 | 1 | 1 | **10** | T2 — Sprint 7 design |
| **RU-P3** | Мир card binding | 2 | 1 | 5 | 4 | **12** | T2 — Sprint 7+ retail track |
| **RU-P4** | SberPay widget | 1 | 3 | 5 | 5 | **14** | T1 — Sprint 7 retail track |
| **RU-I1** | ЕСИА OIDC handoff | 3 | 3 | 3 | 3 | **12** | T2 → **promoted T1 by PO** (gives non-SBBOL retail path) |
| **RU-I2** | Sber ID universal SSO | 2 | 3 | 3 | 3 | **11** | T2 — Sprint 7 |
| **RU-I3** | ЕБС биометрия | 3 | 1 | 1 | 1 | **6** | **Parking lot** (6+ months integration) |
| **RU-I4** | Госключ ЭП | 4 | 2 | 2 | 2 | **10** | T2 — pairs with SBBOL settlement (Sprint 6+) |
| **RU-T1** | 6-НДФЛ retail справка | 4 | 1 | 5 | 4 | **14** | **T1 — Sprint 6 retail track** |
| **RU-T2** | НДС-учёт для B2B fees | 4 | 2 | 5 | 4 | **15** | **T1 — Sprint 5** (compliance asks loudly) |
| **RU-T3** | 1С банк-клиент XML | 3 | 3 | 5 | 4 | **15** | **T1 — Sprint 5 paired with #4.4 CSV** |
| **RU-T4** | ФНС API auto-filing | 3 | 1 | 1 | 1 | **6** | Parking lot |
| **RU-M1** | ЦБ РФ rates feed | 2 | 2 | 5 | 5 | **14** | **T1 — Sprint 5** quick demo win |
| **RU-M3** | БРИКС Pay | 2 | 3 | 1 | 1 | **7** | Parking lot |
| **RU-D1** | Российский банковский календарь | 2 | 2 | 5 | 5 | **14** | **T1 — Sprint 5** |
| **RU-D2** | ЭДО (Диадок/Контур) | 3 | 2 | 2 | 2 | **9** | Parking lot — re-eval when 5+ corp clients live |
| **RU-C1** | 259-ФЗ ЦФА classification memo (= RU-R5) | — | — | — | — | — | dup, see RU-R5 |
| **RU-C2** | Атомайз / Мастерчейн listing memo | 3 | 4 | 4 | 4 | **15** | **T1 — Sprint 6 SA memo** |
| **RU-C3** | Sber DFA platform integration | 4 | 4 | 3 | 2 | **13** | T2 — Sprint 7+ when own DFA platform live |
| **RU-S3** | СберБизнес Эквайринг bridge | 2 | 3 | 2 | 2 | **9** | Parking lot |
| **RU-S4** | Sber CRM KYB-data import | 3 | 2 | 3 | 3 | **11** | T2 — Sprint 7 |
| **RU-U2** | Период охлаждения для крупных операций | 4 | 1 | 4 | 3 | **12** | T2 — Sprint 7 (depends on retail track) |
| **RU-U3** | AML alert на паттерны | 3 | 1 | 5 | 4 | **13** | T2 → **promoted T1 by Compliance** Sprint 6 |
| **RU-X1** | Redis → Ignite (импортозамещение) | 3 | 1 | 5 | 2 | **11** | **Parking lot, deep backlog** (IT-lead 2026-05-18 demote; KeyDB/Dragonfly = first-line defence drop-in) |
| **RU-X2** | Postgres → Pangolin/PgPro | 4 | 1 | 5 | 4 | **14** | **T1 — drop-in test in Sprint 6** |
| **RU-X4** | Минцифры реестр включение | 3 | 3 | 1 | 1 | **8** | T3 — multi-quarter PO+Legal trek |

---

## 4. Final tier list (post-voting + PO promote/demote calls)

### Tier 1 — go into Sprint 5 or 6

| # | Feature | Sprint | Why this slot |
|---|---|---|---|
| RU-M1 | ЦБ РФ rates feed | **5** | Pairs with `dlmm-price-oracle` work, oracle dev already in Spasibo block; demo-day visual win |
| RU-D1 | Российский банковский календарь | **5** | Pairs with custody fee (#3.3 follow-up) and margin-call timing (#4.3 follow-up); quick win |
| RU-T3 | 1С банк-клиент XML | **5** | Direct extension of #4.4 CSV. Accountant feedback gap; BA pushes hard |
| RU-R5 | 259-ФЗ ЦФА classification memo | **5** | SA artifact, doesn't compete with backend slots; blocks future RU-C2/RU-C3 |
| RU-T2 | НДС-учёт для B2B fees | **5** | Compliance ask; small backend (fee config split). Blocks 1С export from being accountant-acceptable. |
| RU-R6 | Самозапрет 115-ФЗ | **6** | Mandatory if we serve retail (SbbolID/ЕСИА user path); pair with Sprint 6 retail track |
| RU-I1 | ЕСИА OIDC handoff | **6** | Non-SBBOL retail path; pairs with Sprint 6 user-facing work |
| RU-U3 | AML pattern-detection alert | **6** | Compliance escalation. Small effort but high reg-readiness signal. |
| RU-C2 | Атомайз / Мастерчейн listing discovery memo | **6** | SA artifact, opens Q4 monetization vector |
| RU-X2 | Postgres → Pangolin/PgPro drop-in test | **6** | Drop-in (Pangolin = Postgres fork). Test in CI matrix. Low effort if drop-in holds. |

### Tier 2 — Sprint 7+ track (logged in SPRINT-PLAN.md)

| # | Feature | Earliest sprint | Trigger |
|---|---|---|---|
| RU-R1 | 152-ФЗ audit | Sprint 7 | Treasury onboarding signed |
| RU-R2 | 115-ФЗ Росфинмониторинг feed | Sprint 7 | First B2B settlement >600k ₽ landed |
| RU-R3 | ЦБ Реестр финплатформ | Sprint 7+ multi-quarter | PO + Legal start now |
| RU-P1 | СБП B2B outbound | Sprint 7 | RU-R4 closed |
| RU-P4 | SberPay widget | Sprint 7+ retail | Retail track unlocks |
| RU-I2 | Sber ID universal SSO | Sprint 7 | Sber ID integration BU contact established |
| RU-I4 | Госключ ЭП | Sprint 6/7 | Pairs with SBBOL settlement code |
| RU-T1 | 6-НДФЛ справка | Sprint 7 retail | First retail user files complaint :) |
| RU-S4 | Sber CRM KYB import | Sprint 7 | After SBBOL phase 2 stable |
| RU-U2 | Период охлаждения | Sprint 7 retail | Retail track unlocks |
| RU-C3 | Sber DFA platform integration | Sprint 7-8 | Own DFA platform GA + RU-R5 memo lands "yes ЦФА" |

### Parking lot (re-evaluation triggers documented)

| # | Feature | Why parked | Re-eval trigger |
|---|---|---|---|
| RU-I3 | ЕБС биометрия | 6+ month integration, low Sprint ROI | When fully-remote KYB becomes business-critical (5+ corp pilots demand) |
| RU-T4 | ФНС API auto-filing | Big reg lift, niche UX win | After Sprint 7 6-НДФЛ generation proves user demand |
| RU-D2 | ЭДО Диадок/Контур | Niche per-client integration | When 5+ corp clients live AND ≥2 use Диадок/Контур |
| RU-D3 | Эквайринг | Speculative | When B2B portal (Sprint 5) shows merchant demand |
| RU-S3 | СберБизнес Эквайринг | Same as RU-D3 | Same |
| RU-X1 | Redis → Ignite | См. отдельный backlog item | Sber Security mandates Redis Inc. exit, OR Ignite SQL Grid track starts |
| RU-X4 | Минцифры реестр | Multi-quarter PO trek | Open application before Sprint 7 |
| RU-M3 | БРИКС Pay | API access uncertain | When BRICS rail issues v1 spec |

---

## 5. Roleplay — who said what (capture for retro)

**BA lead** (открывая брейнсторм):
> Мы 4 спринта строили generic-DLMM. У SwapPage и hedge-калькулятора иконки
> Sber, тексты на русском — но **бизнес-логика выглядит как Solana/Meteora copy-paste**.
> Treasurer на питче спросит «как у вас с СБП», «как у вас с ЦБ Реестром», «куда
> мне НДФЛ показывать» — и мы провалимся.

**Compliance lead** (вето-список):
> 152-ФЗ и 115-ФЗ — **must-have для production**. До этого Treasury не подпишет
> даже paper LP. RU-R5 (ЦФА классификация) — **корень дерева**: ответ определяет
> в какой регуляторный фрейм мы попадаем. Без этого 161-ФЗ и СБП-рельс беспредметны.
> Самозапрет (RU-R6) — обязательно если ЕСИА retail-путь активируем.

**SA** (techical sanity):
> RU-M1 (ЦБ РФ rates) — это **2-3 дня max**. Открытый JSON API на cbr.ru,
> кэш на 24h. Promoted в Sprint 5 не глядя.
>
> RU-D1 (банковский календарь) — есть public-domain `russian-holidays` библиотеки
> на любом языке. Реализация — таблица в БД + сервис методом `isWorkingDay(date)`.
> Полдня.
>
> RU-T3 (1С XML) — есть открытый XSD-стандарт банк-клиент v3.0, 1С его читает
> с 2008 года. Конвертер из нашей `Transaction` сущности — день, плюс тесты.
>
> RU-I1 (ЕСИА OIDC) — Госуслуги OIDC хорошо документирован, **те же 5 дней что
> SBBOL OIDC handoff (#5.B)**. Можно реализовать как ту же шину `dlmm-common/auth/oidc`
> с двумя провайдерами.
>
> RU-X2 (Pangolin/PgPro) — Pangolin это форк PG 14+ с минимальными отличиями.
> **CI matrix-test через testcontainers с двумя БД-образами**. Если интеграционные
> тесты зеленеют — production-ready. Полдня настройки CI.

**PO** (commercial filter):
> Promote RU-I1 в Sprint 6 — не каждый корп клиент в SBBOL, многие
> работают через Госуслуги (особенно ИП-шники < 50 employees). И это
> **B2B revenue, не retail**.
>
> RU-T3 (1С XML) — да, в **каждом** питче следующий вопрос после "сколько" будет
> "как это в 1С импортировать". Sprint 5 must.
>
> RU-X2 promote в Sprint 6 — у нас в реестре Минцифры есть **формальный требование**
> для bids на госконтракты. Sprint 6 нужно показать что мы Pangolin-ready.
>
> RU-C3 (Sber DFA platform integration) **не моё решение** в этом спринте —
> Sber DFA Strategy team ещё не утвердила интерфейс. Park, повторно
> смотрим Q4.

**Junior BA** (вопрос на котором все пожимали плечами):
> *«А что про крипто-ЦФА? Atomyze уже листит "Хорошее", "Сетка", "Слиток".
> Можем мы это листить на DLMM? У нас же есть pool engine с bin'ами — Atomyze
> ЦФА торгует в OTC mode, мы можем дать им вторичный рынок».*

→ **Action**: RU-C2 (Атомайз/Мастерчейн listing discovery memo) добавляется
в Sprint 6 как SA artifact. Если выкатим — **новая monetization line: secondary
market fees on ЦФА** (M-new в monetization strategy).

---

## 6. Что не приняли как тикет в Sprint 5/6, но осталось в формулировке

Чтобы не пропадало в копилку backlog'а:

1. **RU-T2 НДС учёт** — взяли минимальный (отделить НДС от gross fee в `b2b_settlements`), полная ФНС-acceptable отчётность отложена.
2. **RU-U2 период охлаждения** — обсуждали для B2B settlement >50M, отложили: пилотные корп клиенты предпочитают instant, добавим когда 1-я фрод-кейс случится (negative-trigger).
3. **RU-C1 = RU-R5 dedup** — один memo, не два.

---

## 7. Decisions (записать в Sprint 5 / 6 kickoff)

- [✓] Sprint 5 принимает **5 RU-фич**: RU-M1, RU-D1, RU-T3, RU-R5 memo, RU-T2.
- [✓] Sprint 6 принимает **5 RU-фич**: RU-R6, RU-I1, RU-U3, RU-C2 memo, RU-X2 drop-in test.
- [✓] Redis → Ignite (RU-X1) — **deep backlog (parking lot)**, не в Strategic tracks. IT-lead 2026-05-18: монитоить ежемесячно нечего, current Redis surface слишком мал, активатор внешний. KeyDB/Dragonfly drop-in остаётся как first-line defence на случай импорт-замещения.
- [✓] **Parking lot обновляется** parking lot'ом RU-фич с явными re-eval triggers.
- [✓] SPRINT-PLAN.md обновляется в этой же PR, **SPRINT-5-KICKOFF.md создаётся** в этом же PR.

---

## 8. Что НЕ обсуждалось (для retro)

- Распределение work-load по разработчикам в Sprint 5/6 — IT-lead знает capacity.
- Конкретные API URL'ы / endpoints — оставлено для SA в момент кодирования.
- Цены / billing tier'ы для T2-фич — PO + CFO разговор.

---

*Recorded by: BA lead + junior BA scribe. Sign-off: SA, PO, Compliance.
Action items in §7. Ignite memo cross-referenced in
docs/SPRINT-PLAN.md "Strategic tracks" section.*
