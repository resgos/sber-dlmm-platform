# Sber DLMM — Demo materials index

> Готовый набор демо для 5 типов аудитории. Каждый документ —
> самостоятельный скрипт: open в одной вкладке, follow click-by-click,
> Q&A bank в конце.
>
> Создано: 2026-05-22 (Sprint 10 wave 3).

---

## Picker matrix

| Аудитория | Файл | Длительность | Доминантный канал | Доминантная метрика |
|---|---|---:|---|---|
| 🎩 **VP / C-level** (PO, CFO, CIO) | [`01-vp-executive-briefing.md`](./01-vp-executive-briefing.md) | 10 мин + 5 Q&A | live click-through 7 экранов | sprint velocity + revenue runrate |
| 💼 **Investor committee** | [`02-investor-pitch.md`](./02-investor-pitch.md) | 20 мин + 15 Q&A | slides → 5-min live demo | TAM/SAM/SOM + 45× ROI + moats |
| 👤 **Малый бизнес / ИП** (≤100M ₽/год) | [`03-small-business-ip.md`](./03-small-business-ip.md) | 5 мин + 5 Q&A | live demo, 1 окно | конкретная экономия в ₽/год |
| 🏢 **Средний бизнес** (100M–1B ₽/год) | [`04-medium-business.md`](./04-medium-business.md) | 10 мин + 10 Q&A | live demo 4 workflow + numbers | treasury efficiency + 1С интеграция |
| 🏛️ **Крупный бизнес / Enterprise** (1B+ ₽/год) | [`05-enterprise.md`](./05-enterprise.md) | 15 мин + 30 Q&A | strategy first, demo second, tech deep-dive по запросу | tokenization + SLA-MM + multi-sig |

---

## Quick-reference: какие фичи показывать кому

| Фича | VP | Investor | ИП | Средний | Enterprise |
|---|:---:|:---:|:---:|:---:|:---:|
| Login + Dashboard | ✅ | ✅ | ✅ | ✅ | — (admin focus) |
| Swap (basic) | ✅ | ✅ | ✅ | ✅ | mention |
| FX hedge | ✅ | ✅ | ✅ | ✅ | mention |
| Pools listing + detail | mention | mention | mention | ✅ | ✅ |
| LP positions + Health Score | ✅ | ✅ | mention | ✅ | ✅ |
| Position Alerts (N-02) | mention | — | mention | ✅ | ✅ |
| Auto-claim (N-04) | mention | — | ✅ | ✅ | mention |
| Pool Comparator (N-01) | ✅ | — | — | ✅ (share link!) | ✅ |
| Portfolio Rebalancer | mention | — | — | mention | mention |
| Theme toggle / RU-EN | mention | mention | — | mention | — |
| Admin Dashboard | mention | ✅ | — | — | ✅ |
| Admin API Analytics (F-15) | ✅ | — | — | — | ✅ |
| OTC Desk | mention | ✅ | — | — | ✅ |
| Suspicious Transactions | mention | mention | — | — | ✅ |
| Audit Log | — | mention | — | mention | ✅ |
| 1С банк-клиент XML экспорт | — | — | — | ✅ | mention |
| Webhooks / API tiers | — | mention | — | ✅ | ✅ |
| Multi-sig (Sprint 12 mock) | — | mention | — | — | ✅ |
| White-label DLMM-aaS | — | mention | — | — | ✅ |
| SLA-MM contract (F-13) | mention | ✅ | — | — | ✅ |

---

## Pre-demo checklist (для пресенитора)

- [ ] `docker-compose up -d` подняли весь стек, проверили `/actuator/health` на gateway
- [ ] Открыли в браузере 2 вкладки: `http://localhost:3001` (user-ui) + `http://localhost:3000` (admin-ui)
- [ ] Залогинились под demo user'ом В user-ui и под admin (SUPER_ADMIN) в admin-ui — *обе уже верифицированные*
- [ ] Включили **тёмную тему** перед презентацией (профессионально на проекторе) ИЛИ светлую (если в комнате яркое освещение)
- [ ] Браузерные уведомления **разрешены** для localhost — иначе N-02 demo показывает только in-app toast
- [ ] Если demo для Enterprise — открыли `/api-analytics` и сгенерили ~20 пробных swap'ов чтобы counter'ы не были нулевыми
- [ ] Pool Comparator — пред-заготовили URL `/#/pools/compare?p=...&...&...` с 3 интересными пулами
- [ ] Проверили скорость интернета — медленный API → плохой UX → плохая презентация

---

## Демо-сценарии — общие принципы

### "Скажи цифру, потом покажи"
Не "вот наша платформа", а **"вот ваши 7М ₽ экономии в год"** — потом 1 экран, который это доказывает.

### "Один workflow на этап"
Не свопайте через 5 экранов хаотично. Один workflow от начала до конца, потом следующий. Зрителю должно быть видно "что было → что нажали → что стало".

### "Дайте им потрогать"
В конце ИП/средний/Enterprise demo — оставьте 2 минуты "попробуйте сами на demo user'е". Tactile experience продаёт лучше любых слов.

### "Q&A это часть демо, не overhead"
Q&A bank в каждом скрипте — это не справочник, это **подготовленные ответы на 10–20 типовых возражений**. Прочитайте перед встречей.

### "Не извиняйтесь за беты"
Платформа в production-ready состоянии: 254 теста, 5 CI pipelines, реальные пилоты. Не говорите "это пока бета" — это саботаж продаж.

---

## Анти-паттерны (не делать)

- ❌ Открывать админку малому бизнесу — пугает сложностью, не их кейс
- ❌ Объяснять bin/strategy математику инвестору — он хочет numbers + moat
- ❌ Сравнивать с Uniswap V4 крупному бизнесу — они думают про SBP/SWIFT, не DEX
- ❌ Говорить "блокчейн" или "крипта" любой аудитории — пугает RU-аудиторию + некорректно
- ❌ Показывать тёмную тему в светлой комнате с проектором — низкий контраст убивает читаемость
- ❌ Делать live demo через медленный internet — pre-recorded video бэкап на случай провала
- ❌ "Мы это можем добавить за пару дней" — обещание без RFC = долг

---

## Версионирование демо

Эти материалы — snapshot на 2026-05-22. После каждого major sprint:

1. Обновить sprint velocity таблицу в `01-vp-executive-briefing.md`
2. Обновить runrate в `02-investor-pitch.md` §4
3. Если landed новая фича — добавить в picker matrix выше
4. Если landed pilot — обновить count в Enterprise pitch §10 "Сколько других Enterprise клиентов"

Commit message convention: `docs(demo): refresh after sprint N close`.

---

## Контакты для follow-up

После любой встречи отправьте письмо с:

1. Подходящий demo doc (например, `04-medium-business.md` приложением)
2. Sandbox-access credentials (если NDA подписан)
3. Calendar invite для tech deep-dive (если запрошен)
4. Sample contract (для Enterprise — SLA-MM template)

---

— Sber DLMM Platform team · 2026-05-22
