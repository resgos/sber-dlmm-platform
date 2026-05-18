# Sprint 5 Demo — Walkthrough

**Date**: 2026-06-02 (Sprint 5 acceptance day)
**Audience**: PO, CFO, Treasury BU lead, Spasibo BU lead, 2-3 prospect corp-clients
**Duration**: 20-25 минут демо + 15 минут Q&A
**Pre-req**: `cd docker && docker-compose up -d` + wait ~60s for boot

> Theme reminder: Sprint 5 = **SberSpasibo distribution + B2B portal foundations + RU-market-fit**.
> The walkthrough is structured so the headline (Spasibo) lands first, B2B pillar second,
> RU-market features third (as "infrastructure proof", not as separate stories).

---

## Quick-access URLs (open all in tabs before starting)

| What | URL | Login |
|---|---|---|
| **User UI** | http://localhost:3001 | `ivanov@example.com` / `Demo1234` |
| **Admin UI** | http://localhost:3000 | `admin@sber-dlmm.ru` / `Demo1234` |
| **Status page** | http://localhost:8090 | — public |
| **Grafana** | http://localhost:3002 | anonymous viewer |
| **Swagger (token-service)** | http://localhost:8082/swagger-ui.html | — |
| **Swagger (transaction-service)** | http://localhost:8085/swagger-ui.html | — |
| **Swagger (price-oracle)** | http://localhost:8086/swagger-ui.html | — |

---

## Demo flow — 7 acts

### Act 1 — Stage-setting (2 min)

> "Sprint 5 closed 13 of 15 code tickets across 4 thematic buckets.
> Today's walk: SberSpasibo end-to-end → B2B portal foundations → the
> RU-market features that pin everything to actual Russian reality.
> Public status page first so you can see we're up."

**Open**: http://localhost:8090

- Все 9 сервисов зелёные.
- "Это публичная страница, можно показывать клиентам. В проде за status.dlmm.sber-online.ru."
- "Обновляется каждые 30 секунд, без cookies, без CDN — zero supply-chain risk."

---

### Act 2 — SberSpasibo end-to-end (5 min) — **THE HEADLINE**

> "60 миллионов пользователей Спасибо — это TAM, который мы открываем
> в этом спринте. Конверсия баллов в рубли в один клик."

**Step 2.1 — Mint SSPAS via webhook (Postman / curl)**

```bash
# Получаем admin JWT
TOK=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@sber-dlmm.ru","password":"Demo1234"}' | jq -r .accessToken)

# Симулируем webhook от Спасибо BU — "ivanov заработал 1000 баллов"
curl -X POST http://localhost:8080/api/v1/spasibo/webhook \
  -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "a0000000-0000-0000-0000-000000000002",
    "points": 100000,
    "reference": "spasibo-demo-2026-06-02-001"
  }'
```

Show: HTTP 201, `opType: "MINT"`, `points: 100000`.

> "Идемпотентность по reference — повторим тот же запрос."

```bash
# Повтор — должен вернуть тот же row, без дубль-кредита
curl -X POST http://localhost:8080/api/v1/spasibo/webhook \
  -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "a0000000-0000-0000-0000-000000000002",
    "points": 100000,
    "reference": "spasibo-demo-2026-06-02-001"
  }'
```

Show: same id, no double-credit.

**Step 2.2 — User converts via widget**

Login as `ivanov@example.com`. На дашборде сразу видна зелёная карточка
**СберСпасибо**: 1500 баллов (50k seed + 100k минт = 150_000 единиц с decimals=2 = 1500 баллов).

> "Один клик, фиксированный курс 1:1, без комиссии — retail flow."

В виджете ввести `500`, нажать **«В рубли»**.

Should see: success alert, баланс SSPAS уменьшается на 500, баланс SRUB растёт на 500.

> "Под капотом — burn-mint pair в одной JPA транзакции, не идёт через
> pool swap. Поэтому не зависит от ликвидности SSPAS/SRUB пула и
> всегда успешен. Пул из #5.2 остаётся доступен для пользователей,
> которые хотят регулярный swap UX."

---

### Act 3 — B2B portal — issuer KYB + billing (4 min)

> "Второй pillar спринта — площадка для корпоративных эмитентов токенов.
> Покажу через Swagger чтобы было видно все поля."

**Step 3.1 — Register issuer**

Open Swagger: http://localhost:8082/swagger-ui.html → POST /api/v1/b2b/issuers

```json
{
  "inn": "7707083893",
  "legalName": "ООО \"Демо-Эмитент\"",
  "displayName": "Demo Issuer",
  "contactEmail": "ceo@demo-issuer.ru",
  "contactPhone": "+7 495 000-00-00",
  "tier": "PRO"
}
```

→ 201, status = **PENDING**.

> "ИНН валидируется regex'ом (10 для юр.лица / 12 для ИП). Полная
> сверка с ЕГРЮЛ — Sprint 7+ KYB integration."

**Step 3.2 — Admin approves KYB**

```bash
# Capture issuer ID from previous response
ISSUER_ID="<from-step-3.1>"

curl -X POST "http://localhost:8080/api/v1/b2b/issuers/$ISSUER_ID/approve" \
  -H "Authorization: Bearer $TOK"
```

→ status flips **PENDING** → **APPROVED**, `reviewedBy` = admin UUID, `reviewedAt` = now.

**Step 3.3 — Trigger billing manually (don't wait until 1st of month)**

```bash
curl -X POST "http://localhost:8080/api/v1/b2b/billing/run?period=2026-05" \
  -H "Authorization: Bearer $TOK"
```

**Step 3.4 — Show invoice**

```bash
curl -s "http://localhost:8080/api/v1/b2b/issuers/$ISSUER_ID/invoices" \
  -H "Authorization: Bearer $TOK" | jq
```

> "PRO tier: listing 500k + retainer 200k = 700k gross. Из этого 116666 НДС
> (gross × 20/120, floor), 583334 net DLMM выручки. Listing fee charged
> только на первый инвойс — дальше только retainer + volume."

---

### Act 4 — RU-market features: 1С export + НДС split (3 min)

> "B2B клиент обязательно спросит: 'как это в 1С'? Покажу прямо в Swagger."

**Step 4.1 — CSV export (Sprint 4 #4.4 baseline)**

Open: http://localhost:8085/swagger-ui.html → GET /api/v1/transactions/report → execute → download CSV.

> "14 колонок, header frozen. Это baseline с Sprint 4."

**Step 4.2 — 1С export (Sprint 5 #5.11)**

```bash
curl -s "http://localhost:8080/api/v1/transactions/report?format=1c" \
  -H "Authorization: Bearer $TOK" -o demo-1c.txt
cat demo-1c.txt | head -30
```

Show output:
```
1CClientBankExchange
ВерсияФормата=1.03
Кодировка=Windows
Отправитель=DLMM Platform
...
СекцияДокумент=Платежное поручение
Номер=...
Дата=...
Сумма=...
ПлательщикСчет=DLMM-USR-A0000000
ПолучательСчет=DLMM-POOL-...
НазначениеПлатежа=DLMM tx:... type:SWAP fee:...
КонецДокумента
...
КонецФайла
```

> "Это **точный текстовый формат** 1С банк-клиент v1.03, который импортирует
> 1С 8.3 wizard'ом без какой-либо ручной работы. Тестировали на sandbox
> 1С — прошёл без warnings."

**Step 4.3 — НДС split на B2B transfer**

Make a B2B settlement:

```bash
curl -X POST http://localhost:8080/api/v1/transactions/b2b/settlements \
  -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/json" \
  -d '{
    "counterpartyUserId": "a0000000-0000-0000-0000-000000000003",
    "tokenId": "b0000000-0000-0000-0000-000000000001",
    "amount": 1000000,
    "reference": "b2b-demo-2026-06-02-001",
    "notes": "Demo НДС split"
  }'
```

Show response — there are 4 fee fields:
- `grossFeeAmount: 500` (5 bps от 1M)
- `vatAmount: 83` (gross × 20 / 120)
- `netFeeAmount: 417`
- `vatRatePct: 20`

> "Бухгалтер сразу маппит: gross → расход, vat → НДС-к-возмещению,
> net → чистая стоимость услуги. Точно соответствует 1С 8.3
> НДС-учёт схеме."

---

### Act 5 — ЦБ РФ rates + банковский календарь (2 min)

> "Третий блок RU-features — pricing и timing, выровненные на Россию."

**Step 5.1 — CBR spread**

```bash
curl -s http://localhost:8080/api/v1/oracle/spread/USD \
  -H "Authorization: Bearer $TOK" | jq
```

Show JSON: `dlmmMarketRate`, `cbrOfficialRate`, `spreadBps`.

> "DLMM market rate против официального курса ЦБ. Положительный spread —
> DLMM USD дороже официального, отрицательный — дешевле. Treasury desk
> сразу видит арбитражную возможность или объяснимый разрыв."

> "CBR feed обновляется по cron'у в 14:00 МСК + eager-fetch на старте.
> Источник — официальный cbr.ru/scripts/XML_daily.asp, не third-party
> proxy — Sber security flag'ает любые supply-chain hops."

**Step 5.2 — Banking calendar (без UI, в логах)**

> "Custody fee scheduler теперь пропускает накопления в нерабочие дни.
> Покажу в логах token-service когда установим клок на 9 мая (через
> env override для демо)."

```bash
docker exec -e TZ="Europe/Moscow" dlmm-token-service date -s "2026-05-09 12:00:00"  # synthetic
docker logs dlmm-token-service | tail -5
```

Должны увидеть: `Custody fee tick skipped: 2026-05-09 is a non-banking day`.

> "Не теряем proration — на следующий рабочий день accrual покрывает
> elapsed-период автоматически."

---

### Act 6 — Hedge unwind + Margin alerts (3 min)

> "Last frontend pieces — Sprint 4 carry-overs которые делают FX-хедж
> полноценным lifecycle, не one-shot."

**Step 6.1 — Open a hedge (#5.14 + carry from #4.1)**

User UI as ivanov → "Хедж FX". Выбрать SRUB → SUSD. 50% preset. Захеджировать.

> "Свеже-захеджированная позиция тут же попадает в **«Открытые хеджи»**
> внизу страницы."

**Step 6.2 — Unwind hedge**

В таблице "Открытые хеджи" нажать **«Закрыть»** → confirm modal → OK.

> "Reverse swap target FX → SRUB по текущему рынку. Idempotency-key tagging
> ('hedge-{uuid}' / 'unwind-{originalKey}') — открытая позиция выпадает из
> списка автоматически после закрытия."

**Step 6.3 — Margin alerts (#5.15 + carry from #4.3)**

> "Margin watch фоном проверяет позиции каждые 5 минут. Для демо
> симулируем выход из range админ-тулом — двигаем active_bin пула."

Trigger demo: admin-bff endpoint moves active bin or just run a big swap
that drifts the bin. Then wait ~5 min (or trigger scheduler manually
via management endpoint).

В notification-bell на user-ui появляется:
- Красный значок с цифрой
- Pop-up с alert "🔴 Маржин-колл" + message "Позиция X вышла из диапазона...
  Перебалансировать к 2026-06-04."

> "Срок — working-day aware через banking calendar #5.10."

---

### Act 7 — Closing — ЦФА memo + Q&A bank (1 min)

> "Закрывающий блок — невидимая работа, но критичная для прода."

**Show**: `docs/CFA-CLASSIFICATION-MEMO.md` §3 — verdict table:

| Group | Verdict |
|---|---|
| EQUITY | 🔴 ЦФА если real-backed |
| FIAT_BACKED | 🟡 161-ФЗ EMI domain |
| COMMODITY | 🟢 NOT ЦФА (Атомайз precedent для прода) |
| UTILITY (SSPAS) | 🟢 NOT ЦФА |
| INDEX | 🔴 ЦФА если underlying backed |
| SRUB | 🟢 NOT ЦФА (internal unit) |

> "Recommended pilot path: все токены — internal accounting units.
> Production migration к Мастерчейн ОИС для equity-backed — Sprint 7+ track.
> Compliance подписала."

---

## Q&A bank (anticipated questions)

### From CFO

**Q**: «Когда ждать первой выручки от Spasibo лейн?»
**A**: Spasibo BU сейчас на стадии internal-UAT их side webhook'а. Прогноз:
рабочий контур до 31 июля → первые real-money пилоты в августе → полная
прокатка по 60M users — Q4. Конверсионная комиссия retail = 0bps, monetisation
через Spasibo BU rev-share contract (PO has draft).

**Q**: «B2B портал — когда первый платный клиент?»
**A**: 2 sales reps уже показали API двум прospect'ам. Frontend форма
self-service registration — Sprint 6 day 1 (4 дня FE-work). Realistic
первая платная подписка (BASIC tier 50k/месяц + 100k listing) — Sprint 7
(~6 weeks).

### From Treasury BU

**Q**: «Margin call — насколько надёжно?»
**A**: Scheduler 24/7 (не пропускает выходные — risk feature). Cooldown
6h per-position-per-eventType. К Sprint 6 добавится UI link "перейти к
позиции" из alert'а напрямую.

**Q**: «Когда real MOEX feed вместо mock?»
**A**: 4.F MOEX ISS procurement — Sprint 6/7. ЦБ РФ feed (#5.9) уже даёт
official rates сегодня, что закрывает 80% Treasury-side use case (treasurer
видит spread DLMM vs official, не нужен intraday-quote pricing).

### From Compliance

**Q**: «Когда полный 152-ФЗ ПДн audit?»
**A**: Sprint 7 Track 3. Sprint 5 #5.D ЦФА verdict разблокировал планирование
по cascade'у: 152-ФЗ + 115-ФЗ обязательны, 161-ФЗ EMI — только когда
SRUB станет real-backed (партнёрство со СберПэй).

**Q**: «Самозапрет 115-ФЗ амендмент 2024?»
**A**: Sprint 6 #6.7 — уже в backlog'е. Реализуется параллельно с ЕСИА retail-путём.

### From Sales / prospect corp clients

**Q**: «Как выглядит цена?»
**A**: BASIC 100k listing + 50k/мес + 5bps volume. PRO 500k + 200k + 3bps.
ENTERPRISE 2M + 1M + 1bp. НДС включён.

**Q**: «Что если я уже есть на Атомайз или Мастерчейн?»
**A**: Sprint 6 #6.C — discovery memo по их listing. Возможна интеграция
"торгуете ЦФА у Атомайз, ликвидность через нас" — это новая monetization
line, обсудим в Q3.

---

## Run-of-show

| Минута | Кто | Что |
|---|---|---|
| 0-2 | PO | Открытие, status page, контекст спринта |
| 2-7 | IT-lead | Spasibo end-to-end (Act 2) |
| 7-11 | Backend lead | B2B portal + billing (Act 3) |
| 11-14 | BA | 1С + НДС split (Act 4) — accountant angle |
| 14-16 | SA | CBR + календарь (Act 5) |
| 16-19 | Frontend lead | Hedge unwind + margin alerts (Act 6) |
| 19-20 | Compliance lead | ЦФА memo verdict (Act 7) |
| 20-25 | All | Q&A |

---

*Updated: 2026-06-02 SA + PO. Owner of follow-up: PO.
Demo run check-list:*
- [ ] docker-compose up -d (60s wait)
- [ ] All 9 services UP on status page
- [ ] Admin token captured in env
- [ ] Issuer Postman collection imported
- [ ] Spasibo widget shows mint+convert flow
- [ ] Browser tabs in correct order
