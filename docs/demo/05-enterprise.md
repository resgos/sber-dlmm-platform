# Крупный бизнес / Enterprise demo — Sber DLMM Platform

> **Audience:** холдинги, госкомпании, банки-партнёры, крупные федеральные сети. Оборот 1B+ ₽/год. CFO / Treasury Head + IT lead на одной встрече.
> **Persona:** Александр Викторович, treasury director ПАО "Транспортхолдинг" — оборот 25B ₽/год, дочки в 8 регионах, импорт-экспорт CIS + Китай, есть собственный депозитарий + ЦФА оператор партнёрский.
> **Goal:** продать (1) custom tokenization, (2) dedicated SLA market-maker, (3) multi-sig корп-кошельки, (4) white-label DLMM-as-Service.
> **Duration:** 15 минут + 30 минут вопросов + tech deep-dive по запросу.
> **Date:** 2026-05-22.

---

## 1. Hook (60 сек) — крупный клиент хочет видеть не "удобный UI" а "стратегию"

> *"Александр Викторович, для холдингов вашего масштаба наша платформа предлагает 4 пересекающихся capability:*
>
> *1. **Кастомная токенизация** — выпустить корпоративные облигации Транспортхолдинга через ЦФА, торговать ими на нашей DLMM-площадке.*
> *2. **Dedicated SLA Market-Maker** — Sber Treasury гарантирует spread ≤30bps на ваших инструментах 24/7, договорная цена.*
> *3. **Multi-sig корп-кошельки** — 2-of-3 финкомитета подтверждают любую транзакцию >50M ₽; полный audit trail.*
> *4. **White-label DLMM-as-Service** — мы лицензируем платформу под бренд Транспортхолдинга для расчётов с вашими 200+ контрагентами.*
>
> *За 15 минут покажу первые две вживую, обсудим архитектуру остальных. Готовы?"*

---

## 2. Strategic framing (90 сек)

### Why DLMM, not traditional clearing?

| | Traditional (SBP B2B / SWIFT) | DLMM custodial |
|---|---|---|
| Settlement time | T+1 (РФ) / T+2 (cross-border) | T+0 instant |
| Spread | 30–80 bps | 5–10 bps (10–30 bps на cross-border CNY) |
| Counter-party risk | Bilateral | Pool-mediated, no direct exposure |
| Compliance | Manual + KYC paperwork | Auto AML, KYC reusable |
| Liquidity discovery | Bilateral phone calls | RFQ marketplace + OTC desk |
| Cost per transaction | ~3K ₽ + spread | ~30 ₽ + spread |

### Pilots ready to consider

- **Sber Treasury** уже подключается как LP в наши пулы (Sprint 4 #4.A, идёт пилот)
- **Тинькофф, Wildberries Pay** — на стороне (наблюдают)
- **Газпромбанк** — Sprint 11 partnership exploration

> *"Идея простая: для холдингов вашего масштаба Sber становится не просто банком, а инфраструктурой расчётов между вами и вашими 200+ контрагентами."*

---

## 3. Live walk-through (8 минут)

### Шаг 1. Логин как ADMIN/SUPER_ADMIN (20 сек)

Open `http://localhost:3000` (admin-ui) → demo ADMIN user

> *"Это admin-консоль, к которой у Enterprise клиентов есть полный read-access + delegated permissions. Сейчас покажу разделы."*

### Шаг 2. Admin dashboard — макро-картина (60 сек)

→ `/dashboard`

> *"22 пула, ~$13M TVL в сетапе, 210 swap'ов за последние 30 дней. Это сегодняшние seed-данные. В production у вас цифры будут на 2–3 порядка выше."*

→ Показать KPI tiles + revenue charts + TVL distribution

### Шаг 3. OTC desk — block trades (120 сек)

> *"Самое интересное для вас — OTC desk. Допустим, Транспортхолдинг хочет за один блок продать 500K SRUB / купить 5M CNY."*

→ `/otc` → "New OTC quote"
→ Counter-party email picker → "vtb-treasury@vtb.ru" (мокаем)
→ Token in: SRUB 500K; token out: CNY (рассчитывается auto)
→ Expiration: +24h (default after Sprint 10 wave 1 fix)
→ "Create quote"

> *"Quote отправлен; контрагент видит у себя предложение, может принять / отклонить / контр-предложить. Все стадии аудируются: CREATE → QUOTE → ACCEPT → SETTLE."*

→ Show audit log entry → *"вот RFM-готовый audit trail для AML"*

### Шаг 4. API analytics — capacity planning (60 сек)

→ `/api-analytics`

> *"Это то, что вы будете видеть как Enterprise тариф clientes. Throttle ratio per tier. Если у вас уровень throttling > 5% на Enterprise — это сигнал, что мы должны бампить вам лимит или открыть dedicated pod."*

→ Показать панель FREE/PRO/ENTERPRISE с current ratio

### Шаг 5. Подозрительные транзакции + audit (60 сек)

→ `/transactions/suspicious`

> *"Wash-trade detector ловит swap'ы >5% TVL pool'а, >50 tx/min с одного user'а, price-impact >3%. Каждая помечена как 'требует ревью'. Compliance officer кликает 'Просмотрено', tx уходит из очереди + audit log записан."*

→ Click "Просмотрено" на одной → она исчезает

### Шаг 6. User-side для ваших treasurer'ов (90 сек)

→ Open new tab `http://localhost:3001` → user demo

> *"А это интерфейс, с которым работают ваши treasurer'ы изнутри Транспортхолдинга."*

→ `/positions` — показать Health Score
→ `/pools/compare?p=...` — *"вот так наш аналитик сравнивает пулы и шарит ссылку"*
→ `/rebalance` — wizard ребаланса

### Шаг 7. Multi-sig (Sprint 12, mockup) (60 сек)

→ Mention only, no live demo
> *"Multi-sig для корп-кошельков с 2-of-3 финкомитета — на Sprint 12. Спроектировано: каждая транзакция >threshold идёт в pending state, требует подтверждения 2 из 3 уполномоченных. Все подтверждения подписываются JWT, пишутся в audit log, отображаются в admin-консоли как 'pending approval'."*

---

## 4. Custom tokenization — Sprint 12+ opportunity (90 сек)

> *"Идея: ваш холдинг выпускает корпоративные облигации через ЦФА регистрацию. Мы создаём пул TRPL-BOND-2027/SRUB. Sber Treasury market-make'ит, ваши инвесторы свободно торгуют этим инструментом 24/7. Вы получаете primary market через нас, secondary liquidity — through DLMM."*

### Tokenization workflow (Sprint 12)

1. Холдинг → admin-bff: "Я хочу выпустить N млн TRPL-BOND токенов, expiry 2027-12-31, coupon 14% annual"
2. Compliance officer проверяет ЦФА документацию (Sprint 5 #5.D classification memo)
3. Платформа mint'ит токены на холдинг-treasury wallet
4. Sber Treasury получает SLA-MM контракт (F-13): commitment to maintain spread ≤30bps, 24/7 liquidity, в обмен на 50M ₽/год MM fee
5. Retail + institutional инвесторы покупают через `/swap` или OTC

### Revenue for Sber

- Issuance fee: 0.5% of total notional (e.g. 500M ₽ выпуск = 2.5M ₽ Sber)
- Ongoing custodial: 5 bps p.a. on outstanding (500M × 5bps = 250K ₽/год)
- Trading fees: protocol fee 5% of secondary market swaps
- **Estimated value to Sber per корпоративная облигация:** ~5M ₽/год

---

## 5. White-label DLMM-as-Service (60 сек)

> *"Совсем другой угол. Что если ваш холдинг хочет иметь свою собственную DLMM-платформу для расчётов с 200+ дочками и контрагентами — но не строить её с нуля?*
> *Мы лицензируем технологию + операционную поддержку, у вас на белом фоне будет 'Transport DLMM' с вашими цветами, токенами и контрагентами."*

### Pricing (под NDA / договорная)

| | Price |
|---|---|
| Onboarding + первичная настройка | ~30M ₽ one-time |
| Operating fee | 1% of all transaction volumes through your white-label |
| Custom feature requests | договорная (T&M) |
| SLA: dedicated support team | 24/7, 99.9% uptime |

> *"Для оборота 25B ₽/год через white-label = 250M ₽/год revenue Sber. ROI обычно <12 месяцев."*

---

## 6. SLA Market-Maker contracts (F-13) — самый высокий-score feature (60 сек)

> *"И последнее — F-13 SLA-MM. Это flagship Q4 продукт."*

### Что Sber Treasury коммитит

- **Spread guarantee:** ≤30 bps на your pool, 24/7
- **Liquidity guarantee:** TVL minimum 100M ₽ на пуле
- **Uptime guarantee:** 99.9% market presence
- **Fail-over:** в случае ухода Sber Treasury за порог spread'а, automatic refund

### Что получает Sber

- Annual MM fee: договорная (типовая — 50M ₽/год)
- Доля от exit fees (10 bps × volume)
- Right to first-refusal на вашу secondary market activity

### Что получает enterprise клиент

- Гарантия execution для своих корп-операций
- Liquidity для retention своих сотрудников / контрагентов
- Public confidence для своих токенизированных инструментов

---

## 7. Архитектура для Enterprise IT-lead (3 минуты, если на встрече есть IT)

```
                    ┌────────────────────────┐
                    │   Sber DLMM Platform   │
                    │   (your tenant slice)  │
                    └───────────┬────────────┘
                                │
        ┌───────────────────────┼─────────────────────────┐
        │                       │                         │
   ┌────▼────┐         ┌────────▼────────┐       ┌────────▼────────┐
   │ Gateway │         │ Pool engine     │       │ Token service   │
   │ (JWT)   │         │ (DLMM math)     │       │ (custody)       │
   └─────────┘         └─────────────────┘       └─────────────────┘
        │                       │                         │
        ▼                       ▼                         ▼
   ┌────────────┐      ┌────────────────┐         ┌──────────────┐
   │ Your apps  │      │ Postgres 16    │         │ Kafka outbox │
   │ (1C, API)  │      │ + Liquibase    │         │ + replay     │
   └────────────┘      └────────────────┘         └──────────────┘

   Cross-cutting:
   - Prometheus + Grafana per tenant
   - Audit log (admin_audit_log) per tenant
   - Outbox transactional pattern — no event loss даже при Kafka down
   - Resilience4j на каждом downstream call (Sprint 9)
   - JWT validated в gateway + each microservice (defence in depth)
```

### Integration points для Транспортхолдинга

| Ваша система | Наш API |
|---|---|
| **1С УПП / ЗУП** | XML банк-клиент v3.0 экспорт (Sprint 5 #5.11) |
| **СберБизнес** | SSO via SberID (F-25 — Sprint 10-11) |
| **Bitrix24 / другой CRM** | Webhooks на tx.status.changed |
| **Custom corporate dashboard** | OpenAPI 3 + Postman collection |
| **Treasury management software** | RFQ API (Sprint 9 #6.2) для алгоритмических quote'ов |
| **Risk management** | Webhook'и + Prometheus metrics → ваш SOC |

---

## 8. Pricing (Enterprise specific) (45 сек)

| Component | Amount |
|---|---:|
| Enterprise tariff base (API + SLA) | договорная (от 2M ₽/мес) |
| SLA Market-Maker contract (F-13) | от 50M ₽/год |
| Custom tokenization issuance fee | 0.5% of notional |
| White-label DLMM | 30M ₽ onboard + 1% of volume |
| Dedicated account team | included Enterprise+ tariff |
| 24/7 support + on-call escalation | included |

**Typical Enterprise annual spend:** **300M–800M ₽/год** для холдинга вашего масштаба.

**ROI from spread compression alone** (200M ₽ ежемес. оборота × 30bps экономия) = **720M ₽/год** = окупается ~1× contract value через год.

---

## 9. Compliance + regulatory (60 сек)

| Layer | Sber DLMM status |
|---|---|
| **161-ФЗ (НСПК)** | ✅ работаем в frame "internal clearing system" |
| **115-ФЗ (AML)** | ✅ Само-запрет (Sprint 6 #6.7); Sprint 12 — AML SAR auto-file в Росфинмониторинг |
| **152-ФЗ (ПДн)** | ✅ Sprint 10 — full audit + pgcrypto на PII columns |
| **259-ФЗ (ЦФА)** | Sprint 5 #5.D classification memo готова; нет статуса оператора ЦФА сегодня, но готовы к получению |
| **172-ФЗ (Sber-spec)** | ✅ Sber Group internal compliance review pass |
| **ЦБ Реестр финплатформ** | Sprint 11 — phase 1 application |
| **Минцифры реестр отечественного ПО** | Sprint 6 #6.D начали; включение Q1 2027 |
| **GDPR-like delete-me** | Sprint 12 (152-ФЗ flavoured) |

---

## 10. Q&A bank — для крупного бизнеса

**Q: Если Транспортхолдинг выходит из контракта — что с вашими данными о наших операциях?**
A: Data residency policy: ваши данные — ваши. На разрыве контракта: (1) полный экспорт всех ваших транзакций + audit log в S3 dump; (2) 90-дневный grace period; (3) криптошреддинг encryption keys в Sber Vault, после которого даже мы не можем восстановить. Сертифицируется по 152-ФЗ.

**Q: Кто owners каждой строчки кода? IP-вопрос для контрактного review.**
A: Sber AS legal entity owns IP. Используем Apache 2.0 / MIT lib'ы (Spring, AntD, etc.) — список dependencies + лицензии включены в product disclosure. Никаких viral GPL.

**Q: SOC2 / ISO 27001?**
A: Sprint 12 — SOC2 Type I prep + ISO 27001 readiness audit. Сегодня — Sber Group internal compliance frame, которая удовлетворяет 161-ФЗ + 152-ФЗ требованиям.

**Q: Если мы хотим on-premise deployment (нет cloud)?**
A: Не базовый use case, но возможно для Enterprise+. Стек: Java 21 + Postgres 16 + Kafka 7.6 + Redis 7 — всё standard. Helm chart Sprint 10. Минимум 3 серверов × 32GB RAM. Стоимость on-prem development: ~50M ₽ one-time engineering effort.

**Q: Гарантия non-discriminatory market-making на нашем токене?**
A: SLA-MM contract содержит clause: Sber Treasury не делает разницы между Транспортхолдинг counter-parties и общим retail. Audit log зафиксирует любое отклонение от spread guarantee — основание для refund.

**Q: Что если ЦБ потребует public reporting наших токенизированных bond транзакций?**
A: Sprint 9 #6.5 OTC desk уже зашит compliance-ready: amount/time/parties для каждой block trade. Прямой export в формат ЦБ Реестр финплатформ (Sprint 11+).

**Q: Custodial — где физически хранятся приватные ключи?**
A: НЕТ приватных ключей в крипто-смысле. SRUB и token balances — балансовые записи в Postgres + Sber Backup. Аутентификация — JWT через Sber Vault (Sprint 11). Это банковский custody, не self-custody.

**Q: Performance — какой throughput?**
A: Sprint 7 k6 baseline: 100 RPS sustained, p99 < 1s swap latency. Sprint 11 target: 1000 RPS. Bottleneck — Postgres row lock на same-pool concurrent swaps (R#20); решается optimistic-lock + per-bin granularity Sprint 12.

**Q: Disaster recovery — RPO/RTO?**
A: Сегодня dev-grade: pg_dump nightly (TD-6 runbook). Sprint 11 production: WAL-G + Sber Cloud Object Storage cross-region replication. Target RPO 5 min, RTO 1 hour. Документировано в OPS-DB-BACKUP-RUNBOOK.

**Q: Можем ли мы видеть исходный код (security review)?**
A: Под NDA — да, для Enterprise+ контрактов. GitHub access на read-only repository slice. Также проводим quarterly security briefings для Enterprise tier клиентов.

**Q: Сколько других Enterprise клиентов у вас сейчас?**
A: В pilot: 3 (под NDA). Production launch для Enterprise — Sprint 10-11. Вы потенциально будете в первых 10. Это значит — direct line с product team на первый год.

**Q: API rate limit — что если у нас миллион клиентов которые делают свопы?**
A: Enterprise tariff = 1000 RPS sustained, burstable до 5000 RPS на 1 min windows. Если потребуется больше — dedicated infrastructure (separate Postgres tenant), цена по запросу.

**Q: Что если нам нужна совершенно custom feature — например, integration с нашей legacy ERP на Cobol?**
A: T&M engagement; типовая ставка 200K ₽/PD (developer-day). Среднее custom feature — 20–40 PD. Прозрачное scoping через RFC процесс.

**Q: Какова была бы транзитная схема — мы существующий treasury workflow, а параллельно DLMM?**
A: Phase 1 (3 мес): sandbox + 10M ₽ allocation для одного workflow (B2B settlements с топ-10 контрагентами). Phase 2 (3 мес): scale to 100M ₽ + добавить FX hedge. Phase 3 (6 мес): full migration + SLA-MM contract. Параллельно — legacy остаётся, постепенно отжимается.

**Q: Кому платить — Sber или вашему отдельному юрлицу?**
A: Sber. Контракт по нашей DLMM — additional agreement к вашему текущему расчётно-кассовому соглашению с Sber. Один TIN, одна inn, одна юр-сторона.

**Q: ESG / sustainability reporting?**
A: Sprint 11 — green-pool overlay для ESG-flagged токенов. Reporting в ваши ESG отчёты через webhook'и. Текущая платформа сама — energy-efficient (нет PoW, нет mining; чисто DB транзакции).

---

## 11. Закрытие (45 сек)

> *"Александр Викторович, давайте предложу 3-этапный план для Транспортхолдинга:*
>
> *• **Этап 1 (Sprint 10–11, 6 недель):** sandbox-access для вашей IT-команды; запускаем pilot B2B settlement с топ-5 контрагентами; объём pilot — 100M ₽/мес. NDA + sandbox-only.*
>
> *• **Этап 2 (Sprint 11–12, 12 недель):** production with Enterprise тариф 2M ₽/мес; SLA контракт на settlement workflow; FX hedge launched. Целевой run-rate — 50M ₽/мес экономии для вас.*
>
> *• **Этап 3 (Sprint 12+, Q2 2027):** SLA-MM contract на ваши инструменты (50M ₽/год Sber); evaluation white-label DLMM-as-Service.*
>
> *Эта последовательность — стандартный Enterprise onboarding. По итогу — Транспортхолдинг получает $700М+ ₽/год экономии при $300М/год спенде у Sber. ROI ~2× в первый год."*

> *"Готов рассмотреть детальный технический deep-dive — кого подключить с вашей стороны?"*

---

## 12. Что НЕ рассказывать на этом уровне

- ❌ "Это пилот" — фокусируемся на production-ready features
- ❌ Bin/strategy математика — оставляем для IT deep-dive
- ❌ Слишком много про DEX-аналоги — крупный бизнес видит нас как Sber-инфраструктуру, не как "крипто-проект"
- ❌ Sprint планы детально — крупному клиенту нужен product roadmap, не engineering velocity
- ❌ "Мы сэкономили деньги на Docker pruning" — не их уровень обсуждения

---

## 13. Follow-up материалы (отправить после встречи)

- [ ] Enterprise pricing sheet (под NDA)
- [ ] Sample SLA Market-Maker contract template
- [ ] Architecture deep-dive deck (если IT-lead запросил)
- [ ] Security questionnaire response (SIG Lite или CAIQ)
- [ ] References: 2 existing pilots под NDA (с их разрешения)
- [ ] Calendar invite для tech deep-dive (если запрошен)
- [ ] Sandbox access — provisioned в течение 48 часов после NDA signed

— Sber DLMM Platform team
