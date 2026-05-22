# Investor pitch — Sber DLMM Platform

> **Audience:** investor committee (Sber Group strategic investments, internal VC, или external if Series spins out).
> **Goal:** convince that this is a 5–10× return opportunity inside 24 months at well-bounded risk.
> **Duration:** 20 minutes + 15 Q&A.
> **Date:** 2026-05-22.

---

## 1. The pitch in one paragraph

> Sber DLMM — это первая в России регулируемая платформа централизованного маркет-мейкинга для рублёвых токенизированных активов, построенная как back-end слой Сбера для retail + corporate liquidity. Мы зарабатываем на четырёх взаимоусиливающих потоках (protocol fee, FX hedge, B2B settlement, корпоративный treasury LP), за 6 спринтов от прототипа дошли до 30M ₽/год run-rate с траекторией **3.5–4.5B ₽/год к Q3 2027**, и сидим на двух defensible moats, которые конкуренты не воспроизведут без Sber-distribution.

---

## 2. The market (TAM / SAM / SOM)

### TAM (₽-tокенизация в РФ к 2028)

| Сегмент | TAM (₽/год) | Источник |
|---|---:|---|
| Корпоративные расчёты (T+0 vs SBP T+1) | 8–12T ₽/год оборота × 50bps spread = 40–60B revenue pool | ЦБ РФ, NSP volume reports |
| Retail token swap (аналог Wildberries Pay + brokerage) | 200M+ пользователей × ~50K ₽/год avg × 30bps fee = 30B revenue pool | Sber Online MAU |
| FX-хедж для МСП (импорт-экспорт с Китаем после санкций) | 100K компаний × ~10M ₽/год × 5bps = 5B revenue pool | МЭР статистика 2025 |
| Токенизированные облигации / гос-долг через ЦФА | 1T ₽ к 2028 (ЦБ прогноз) × 25bps = 2.5B revenue pool | ЦБ proceeding paper |
| **Итого TAM** | **77–94B ₽/год** | |

### SAM (что РЕАЛИСТИЧНО можно взять с Sber distribution)

- Sber Online 100M users × 0.5% активность через DLMM = **500K пользователей** × ~30K ₽/год комиссии = **15B revenue pool**
- Sber B2B (СберБизнес) 4M юр-лиц × 5% активность = **200K корпов** × ~200K ₽/год спрэд + fee = **40B revenue pool**
- **Итого SAM:** ~55B ₽/год

### SOM (что МЫ возьмём в первые 24 месяца)

- Q2 2027 target: **3.5–4.5B ₽/год** = **6–8% доли SAM**
- Это консервативно: SberSpasibo writeback подключает 60M users пассивно (1% активность = 600K)

---

## 3. Бизнес-модель — 4 потока, которые усиливают друг друга

```
    ┌────────────────────┐     fee     ┌──────────────┐
    │  Retail user swap  │ ─────────→  │              │
    └────────────────────┘             │   Protocol   │
                                       │  fee 5% on   │
    ┌────────────────────┐  fee + LP   │  top-3 pools │
    │  Corp FX hedge     │ ─────────→  │              │
    └────────────────────┘             │   30M ₽/год  │
                                       │   today      │
    ┌────────────────────┐  spread     │              │
    │  B2B settlement    │ ─────────→  │              │
    └────────────────────┘             └──────────────┘
                                              ↓ feeds
    ┌────────────────────┐  fee yield  ┌──────────────┐
    │ Treasury LP        │ ─────────→  │  Sber TVL    │
    │ (corp idle cash)   │             │  flywheel    │
    └────────────────────┘             └──────────────┘
                                              ↓ attracts
                                       more retail + corp users
```

### Unit economics (per ₽ TVL)

| Поток | Rate | Sber take | LP/User take |
|---|---|---:|---:|
| Protocol fee (swap) | 5% of 30bps base fee | 1.5 bps | 28.5 bps к LP |
| Custody fee (idle balance) | 5 bps p.a. | 100% | 0 |
| Exit fee (LP close) | 10 bps one-time | 100% | 0 |
| FX hedge spread | ~30 bps round-trip | 50% | 50% |
| B2B settlement | 5–10 bps | 100% | 0 |
| SberSpasibo writeback | 10 bps swap | 100% | 0 |

**Margin profile:**
- Gross margin 99%+ post-launch (infra ~150K ₽/мес vs revenue 800M+ ₽/год)
- Customer acquisition cost: **0** через Sber Online SSO (F-25)

---

## 4. Trajectory (cumulative revenue model)

| Quarter | Stack at end | Run-rate (₽/year) |
|---|---|---:|
| Q3 2026 (now) | protocol fee + FX hedge pilot + Spasibo writeback BU sandbox | **30–80M** |
| Q4 2026 | + 3 SLA-MM contracts + SberID SSO live + Telegram bot + Index funds | **1.5–2.0B** |
| Q1 2027 | + AML SAR auto-file moat + Market surveillance + Tax export | **2.2–2.8B** |
| Q2+ 2027 | + Multi-sig corp wallets + DLMM SDK + Tokenized bonds pilot | **3.5–4.5B** |

**Doubling cadence:** revenue ~2× per quarter through 2026 H2, slowing to 1.5× through 2027.

**Sensitivity:**
- Base case 3.5B Q3 2027 = **above plan** if SLA contracts close on time
- Bear case 2.0B Q3 2027 = SLA контракты slip 2Q, SberID timeline slips → still 65× current
- Bull case 5.5B Q3 2027 = MOEX считает нас официальной площадкой для ЦФА (см. tail risk)

---

## 5. Defensible moats

### Moat 1 — Sber distribution (the killer)

| | Sber DLMM | Tinkoff DLMM (hypothetical) | DEX foreign (Uniswap-style) |
|---|---|---|---|
| User base on launch | 100M (Sber Online SSO via F-25) | 50M (Tinkoff app) | 0 RF users |
| Loyalty multiplier | 60M Spasibo writeback | None | None |
| Corp B2B access | СберБизнес 4M юр-лиц | Tinkoff Business <500K | 0 |
| Regulatory | Sber legal team, ЦФА memo готова | TBD | Blocked by FATF Travel Rule + ЦБ |

**This is not fungible.** Tinkoff would need 12–18 months to onboard a fraction of our distribution; foreign DEXes are blocked outright.

### Moat 2 — Compliance ground floor (the slow burn)

- Sprint 5 ЦФА memo + Sprint 12 AML SAR auto-file = единственная RF DLMM, готовая к ЦБ Реестру финплатформ
- **Когда регулирование придёт** (наш базовый сценарий: ЦБ публикует DEX/DLMM рамку в 2027 H1), мы единственные, у кого compliance battery собрана. Прочие тратят 6–9 месяцев догоняя.

### What is NOT a moat (the honest part)

- Сам DLMM-код — мы реализовали Trader Joe LB whitepaper. Tinkoff повторит за квартал
- React UI — commoditised, design-tokens разница не выживет 2 версии Plasma
- Java / Spring backend — отраслевой стандарт

**Moats живут в дистрибуции, регулятике и time-to-market.** Все три — *Sber-specific*.

---

## 6. Конкурентный ландшафт

| Игрок | Threat level | Why we win |
|---|---|---|
| **Tinkoff Invest** | 🟡 Med | Нет ЦФА memo, нет B2B rail; ребрендинг под Yellow Brand отвлекает фокус через mid-2026 |
| **VTB** | 🟢 Low | Внутренняя R&D на 18 месяцев позади, нет retail UX |
| **MOEX** | 🟡 Med | Может стать партнёром (CFA platform integration), не конкурентом |
| **Foreign DEXes (Uniswap V4, etc.)** | 🟢 Low | Travel Rule + ЦБ blocking; никакой rouble pair |
| **Crypto-native CIS (1inch RU, Curve, etc.)** | 🟢 Low | Не подходят под 161-ФЗ ЦБ требования |

---

## 7. Risks (the slide nobody else shows you)

| Risk | Likelihood | Severity | Mitigation |
|---|---|---|---|
| **ЦБ объявляет полный запрет DEX/DLMM в РФ** | Low (Sber lobbying) | Cataclysmic | Pivot to pure-internal Sber Treasury LP — теряем retail, сохраняем B2B (≥1B ₽/год) |
| Sber Online BU отказывается от SSO интеграции | Med (BU politics) | High | F-13 SLA контракты + retail email/password fallback компенсируют 60% impact |
| Tinkoff публикует аналог раньше Q4 2026 | Low | Med | Они не публиковали ничего за 18 месяцев; нет evidence |
| Same-pool row-lock contention при scale | Low (k6 verified) | Med | Optimistic lock landed Sprint 4; 2 ещё k6 round-trip Sprint 10–11 |
| Pool exploit / smart-contract-like bug | Low (нет SC; pure Java) | High | OWASP review Sprint 11; SOC2 audit prep Sprint 12 |
| Key dev attrition | Med | High | Pair-coding + RFC discipline + полная code review культура |

**The honest one:** мы не сможем повторить запуск, если Sber изменит strategic priorities. Это inherent в "first-party Sber product" модели.

---

## 8. The ask

| | Amount | Cost | Outcome |
|---|---|---|---|
| **Capex** | 0 | (existing Sber Cloud) | — |
| **Opex Q3+Q4 2026** | ~25M ₽ | 2 backend + 1 FE + 1 SRE + 1 SA = 5 FTE × 6 мес × ~800K ₽/мес fully-loaded | Sprint 10–13 delivery (Telegram bot, SberID, multi-sig, AML SAR) |
| **Marketing budget Q4** | ~5M ₽ | Sber Online integration launch campaign | F-25 multiplier activation |
| **External legal** | ~3M ₽ | ЦФА classification + 161-ФЗ + ЦБ Реестр финплатформ | Compliance moat |
| **Итого Q3-Q4** | **33M ₽** | | Target: **1.5–2.0B ₽/год** by Q4 close |

**ROI:** 1.5B ₽ run-rate / 33M ₽ investment = **45× year-1 ROI**. На горизонте 24 месяцев — 100×+.

---

## 9. The live demo (5 min after the slides)

Open `http://localhost:3001` (user-ui) and walk:

1. **Login** demo@sber.ru — already KYC verified
2. `/swap` — SRUB → SBER, 10K ₽, quote shows 0.3% fee, execute
3. `/positions` — show LP positions with Health Score column (новая фича)
4. `/pools/compare?p=...` — сравнение 3 пулов с зелёной подсветкой победителя; копируете ссылку — *"вот так treasurer может скинуть аналитику коллеге в Слак"*
5. `/profile` — переключите тёмную тему → переключите RU/EN → покажите KYC re-verify CTA

Then `http://localhost:3000` (admin-ui):

6. `/dashboard` — реальные seed данные (210 swap'ов, $13M TVL, 22 pools)
7. `/api-analytics` — throttle ratio per tier — *"вот сколько мы будем брать с Pro и Enterprise клиентов"*
8. `/otc` — block trade quote с counter-party email picker
9. `/transactions/suspicious` — wash-trade detector с reviewed_at workflow

---

## 10. Q&A bank

**Q: Что произойдёт, если ЦБ опубликует требование "DLMM = биржа" с лицензией ЦБ?**
A: Sprint 9 #6.3 OTC desk + Sprint 9 RFQ marketplace = регулируемая часть платформы. Останется retail swap → переключим в pure internal clearing (вернёмся в ЦФА frame). Регулировано — да, заблокировано — нет.

**Q: Sber как acquirer — какой % группа возьмёт за SBP-style settlement vs наш B2B rail?**
A: SBP режет 0% retail acquirer fee для B2C, но не для B2B. Наш rail @ 5–10bps spread = ~2× cheaper than SBP B2B + T+0 vs T+1. Sber Group strategic interest = занять nichu которую SBP не покрывает.

**Q: Почему сейчас, а не через год?**
A: Tinkoff и VTB готовят аналоги (intelligence по job postings + Telegram leaks). Time-to-market lead ~6 месяцев. Q4 запуск с SberID SSO забронирует 80% retail ниши до того, как они опубликуются.

**Q: Liquidity bootstrap — откуда возьмётся TVL пока Treasury не подписан?**
A: Seed: $13M TVL уже в test environment (22 pools, 6 token families). Production seed funding ~50M ₽ из Sber Treasury временного allocation (без SLA контракта). LP fees сами разгоняют — через 90 дней TVL ≥ 200M.

**Q: Что вы делаете с pegged tokens (SRUB) — это не stablecoin, это IOU Sber?**
A: SRUB = балансовая запись Sber на счёт пользователя в Sber. 1 SRUB ↔ 1 RUB через `dlmm-token-service.mint/burn` через банковский расчётный счёт. Юридически — депозитная запись, не цифровой актив. ЦБ memo (Sprint 5 #5.D) подтверждает frame.

**Q: Какой ваш net retention / churn?**
A: Не публикуем — мы in pilot, не у retention metric pool depth. Q4 2026 — первая когорта измерима. Промежуточная метрика: 80%+ swap retention week-over-week у demo users.

**Q: Если выходить на инвесторов вне Сбера — что мешает?**
A: 49.x% retention в Sber Group обязательная по charter; ЦБ требует Sber-affiliated owners для finplatform license; крупные RU PE (АФК, Renaissance) ОК на 25%, foreign LPs — нет (FATF complications).

---

**Closing line:** *"Регулируемая DLMM-платформа на рублёвых активах — это не "если", а "когда". И за первый ход в этой нише платит инвестор, который входит сейчас. Готовы заходить?"*

— Sber DLMM Platform team
