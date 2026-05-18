# Sprint 6 Demo — Walkthrough

**Date**: 2026-06-16 (Sprint 6 acceptance day, immediately after acceptance meeting)
**Audience**: PO, CFO, Treasury BU lead, Compliance lead, MOEX representative observer, 2 prospect corp clients (B2B portal lead candidates)
**Duration**: 12-15 минут демо + 10 минут Q&A
**Pre-req**: `cd docker && docker-compose up -d` + wait ~60s for boot

> Theme reminder: Sprint 6 = **compliance core + carry-overs + Spasibo
> design**. Less new visual material than Sprint 5 (most work was
> backend + compliance + memos), but the items that DO have UI
> (самозапрет panel, hedge mass-action, margin deep-link) directly
> address regulatory + risk-committee concerns that Treasury / Compliance
> raised in Sprint 5 demo Q&A.

---

## Quick-access URLs (open in tabs before starting)

| What | URL | Login |
|---|---|---|
| **User UI** | http://localhost:3001 | `ivanov@example.com` / `Demo1234` |
| **Admin UI** | http://localhost:3000 | `admin@sber-dlmm.ru` / `Demo1234` |
| **Status page** | http://localhost:8090 | — public |
| **Grafana** | http://localhost:3002 | anonymous viewer (k6 #5.F results) |
| **Swagger pool-engine** | http://localhost:8083/swagger-ui.html | — |
| **Swagger user-service** | http://localhost:8081/swagger-ui.html | — |
| **GitHub Actions** | https://github.com/.../actions/workflows/backend.yml | — |

---

## Demo flow — 6 acts

### Act 1 — Stage-setting (1 min)

**Open**: status page http://localhost:8090

> "Sprint 6 закрыл 9 из 11 кода-тикетов. Три отложены на Sprint 7 —
> все три на внешних gate'ах (SBBOL Q4 sandbox, дизайнер mockups,
> ЕСИА зависит от SBBOL). Сегодня показываю что реально приземлилось.
> Public status page Sprint 5 #5.8 — все 9 сервисов зелёные."

Quick note: «k6 5.F staging run прошёл — `swap_errors 2.1%`, `p99 847ms`,
retry loop max 3 (Optimistic lock #4.7 verified)». **R#28 закрылся.**

---

### Act 2 — Protocol fee activation (#3.1 + #3.2) — **THE REVENUE UNLOCK** (3 мин)

> "Sprint 3 #3.1+#3.2 ждали legal memo 6+ месяцев. Sprint 5 #5.G —
> compliance ответили: ≤5% остаётся в internal-clearing reg-frame.
> Активируем сейчас live."

**Step 2.1 — Show current pool state via Postman**

```bash
TOK=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@sber-dlmm.ru","password":"Demo1234"}' | jq -r .accessToken)

# Pick a test pool — SBER/SRUB
POOL_ID="c0000000-0000-0000-0000-000000000101"

curl -s "http://localhost:8080/api/v1/pools/$POOL_ID" \
  -H "Authorization: Bearer $TOK" | jq '.pool | {id, baseFeeBps, protocolFeePct, totalFeesCollectedX, totalProtocolFeeX}'
```

Output (pre-activation):
```json
{
  "id": "c0000000-...",
  "baseFeeBps": 10,
  "protocolFeePct": 0,         ← admin не включил ещё
  "totalFeesCollectedX": 12345,
  "totalProtocolFeeX": 0
}
```

**Step 2.2 — Admin enables 5% protocol fee**

```bash
curl -X PUT "http://localhost:8080/api/v1/pools/$POOL_ID/protocol-fee-pct" \
  -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/json" \
  -d '{"protocolFeePct": 5}'
```

→ 200 OK, pool updated.

> "Cap `@Max(5)` в DTO — попытка 6% возвращает 400 с legal memo напоминанием."

```bash
curl -X PUT "http://localhost:8080/api/v1/pools/$POOL_ID/protocol-fee-pct" \
  -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/json" \
  -d '{"protocolFeePct": 6}'
```

→ 400 VALIDATION_ERROR: "protocolFeePct must be in [0..5] per 3.A legal memo".

**Step 2.3 — Execute a swap, show split accumulation**

User-side (ivanov@example.com): SwapPage → SBER → SRUB → 10000 → swap.

Then back on admin Postman:
```bash
curl -s "http://localhost:8080/api/v1/pools/$POOL_ID" \
  -H "Authorization: Bearer $TOK" | jq '.pool | {totalFeesCollectedX, totalProtocolFeeX}'
```

Should now show:
```json
{
  "totalFeesCollectedX": 12375,    ← +30 gross fee
  "totalProtocolFeeX": 1          ← 5% of 30, floor
}
```

> "Сырая комиссия 30 единиц = 28 LP + 2 protocol (округление вниз).
> Sprint 8 treasury sweep job будет периодически осушать `totalProtocolFee*`
> в admin treasury wallet."

**Revenue context**: «при текущем baseline volume 50M ₽/день × 22 seed
пула × 5% protocol fee → ~45M ₽/year run-rate accrual только от этого.
Adds к Q3 400-650M revised target из revenue research.»

---

### Act 3 — Самозапрет (#6.7) end-to-end UI (3 мин)

> "Compliance: 'когда самозапрет?' — отвечаем сейчас, live demo
> полного lifecycle."

**Open**: User UI as ivanov → `/profile` → scroll to «Самозапрет на новые позиции (115-ФЗ)» panel.

**Initial state:** «🔓 Не установлен», большая красная кнопка
«Установить самозапрет».

**Step 3.1 — Set**

Click «Установить самозапрет» → modal с warning «снять можно только
через 7 дней» → введём причину «Перерыв в торговле на 2 месяца» →
Подтвердить.

→ Panel переключается: «🔒 Активен», warning Alert «открытие новых
позиций запрещено», timeline снизу показывает SET event.

**Step 3.2 — Try a swap (should fail)**

В новой вкладке → /swap → SBER→SRUB → 1000 → Обменять.

→ Red alert: «Установлен самозапрет (115-ФЗ). Новые позиции запрещены.
Запросите снятие через /профиль (период охлаждения 7 дней).»

(HTTP 403 USER_SELF_RESTRICTED под капотом.)

> "Кстати, **закрыть existing positions всё ещё можно** — это
> регуляторный intent. Restricted user может разорвать существующие
> позиции, не может открывать новые. Покажу — `/positions` → Remove
> Liquidity → проходит."

**Step 3.3 — Request lift**

Назад в /profile → «Запросить снятие самозапрета».

→ Panel переключается: amber Alert «Период охлаждения. Снятие будет
доступно 2026-06-23 (через 7 дней)». Timeline добавляет LIFT_REQUESTED.

> "В реальности теперь 7 дней ждём. Для демо я хакну backend — поставлю
> `dlmm.self-restriction.lift-cooling-days=0` в env override."

(Behind-scenes: `docker exec dlmm-user-service env DLMM_SELF_RESTRICTION_LIFT_COOLING_DAYS=0` — restart).

→ Refresh ProfilePage → panel переключается: green Alert «Период охлаждения
истёк. Завершить снятие» button.

**Step 3.4 — Finalise lift**

Click «Завершить снятие» → Timeline добавляет LIFTED. Panel back to
«🔓 Не установлен».

Try swap again — works.

> "Полный lifecycle SET → LIFT_REQUESTED → cooling → LIFTED → can re-SET.
> State machine append-only, immutable history visible в timeline для
> compliance audit."

---

### Act 4 — AML pattern detection (#6.9) (3 мин)

> "Sprint 7 Track 3 будет real-time Росфинмониторинг feed. Sprint 6 —
> proactive layer перед этим: видим suspicious patterns до того как
> они становятся reportable."

**Step 4.1 — Synthetic transaction sequence**

В Postman прогоняем 3 swap'а ivanov × 200_000 SRUB → SBER подряд
(в пределах 1 часа):

```bash
for i in 1 2 3; do
  curl -X POST http://localhost:8080/api/v1/pools/swap \
    -H "Authorization: Bearer $IVANOV_TOK" \
    -H "Content-Type: application/json" \
    -d "{
      \"poolId\": \"$POOL_ID\",
      \"tokenInId\": \"b0000000-0000-0000-0000-000000000001\",
      \"amountIn\": 200000,
      \"minAmountOut\": 0,
      \"idempotencyKey\": \"demo-aml-$i-$(date +%s)\"
    }"
  sleep 1
done
```

3 транзакции CONFIRMED.

**Step 4.2 — Trigger AML scan manually (вместо 15-min ожидания)**

```bash
docker exec dlmm-transaction-service \
  curl -X POST http://localhost:8085/actuator/scheduledtasks/dlmm.aml.scan
```

(или просто wait 15 минут — на демо forecast manual trigger).

**Step 4.3 — Show fired alerts in DB**

```bash
docker exec dlmm-postgres psql -U dlmm -d dlmm -c \
  "SELECT pattern, severity, transaction_count, total_amount, evidence_json
   FROM aml_alerts WHERE user_id='a0000000-0000-0000-0000-000000000002'
   ORDER BY detected_at DESC LIMIT 5;"
```

Output (2 строки):
```
       pattern           | severity | tx_count | total_amount | evidence
-------------------------+----------+----------+--------------+-------------
 ROUND_AMOUNT_REPEATS    | MEDIUM   | 3        | 600000       | {"amount":200000,"count":3}
 SUB_THRESHOLD_SPLIT     | HIGH     | 3        | 600000       | {"sum":600000,"count":3,"threshold":600000}
```

> "Два детектора сработали на одних и тех же 3 транзакциях. Cooldown
> 4h означает что в следующем 15-min тике эти же alert'ы не будут
> fire'нуть."

**Step 4.4 — Compliance log + outbox event**

```bash
docker logs dlmm-transaction-service 2>&1 | grep "AML ALERT FIRED" | tail -3
```

Output:
```
WARN AML ALERT FIRED user=a000...002 pattern=ROUND_AMOUNT_REPEATS severity=MEDIUM txCount=3 totalAmount=600000 alertId=...
WARN AML ALERT FIRED user=a000...002 pattern=SUB_THRESHOLD_SPLIT severity=HIGH txCount=3 totalAmount=600000 alertId=...
```

Outbox dispatcher отправит эти в `compliance-events` Kafka topic;
Sprint 7+ — notification-service consumer создаст compliance email.

---

### Act 5 — Hedge mass-action (#6.14) + margin deep-link (#6.15) (2 мин)

> "Treasurer-side QoL улучшения которые BA flag'ала в Sprint 5
> acceptance."

**Step 5.1 — Open 3 hedges**

User UI as ivanov → /hedge → SRUB→SUSD → 25% preset → Захеджировать
(repeat × 3 с разными парами SUSD/SEUR/SCNY).

«Открытые хеджи» table снизу растёт до 3 строк.

**Step 5.2 — Close all**

Click **«Закрыть всё»** в правом верхнем углу Card → modal:
- «Будут последовательно закрыты **3** открытых хеджа на общую сумму
  **3 000 000 SRUB**»
- warning «операция необратима, проскальзывание не ограничено»
- secondary text «хеджи закрываются по очереди, не параллельно —
  предсказуемое влияние на пулы»

Click «Закрыть все 3» → серийный unwind. Table empties.

> "Sprint 7 frontend добавит мини-progress (1 of 3, 2 of 3, 3 of 3)
> — Sprint 6 минимальная версия в обмен на ship-это-же-day-3."

**Step 5.3 — Margin alert deep-link**

(Если есть существующий MARGIN_WARNING в notifications — иначе trigger
admin endpoint moves active_bin out of range)

Click notification 🔔 → найти MARGIN_CALL/WARNING entry → клик
**«Перейти к позиции →»**.

→ Navigate to `/positions?highlight=<positionId>`. (PositionsPage
полная интеграция с highlight — Sprint 7 follow-up R-UX-036, ~1h work.)

---

### Act 6 — Pangolin CI + Spasibo memo (1.5 мин)

> "Stretch goals — CI matrix и design work."

**Step 6.1 — Pangolin CI screenshot**

Open GitHub Actions latest backend.yml run:

| Leg | Status |
|---|---|
| build-and-test (postgres) | ✅ green (mandatory) |
| build-and-test (pangolin) | ⚠ 1 failure InvariantTest BIGINT (advisory) |

> "Postgres leg обязательный, Pangolin advisory с continue-on-error.
> 1 InvariantTest fails — Pangolin BIGINT slightly differently
> overflow'ит. R-Pangolin-1 logged for Sprint 7 SRE. Build URL
> attached to Минцифры phase-1 form (#6.D submitted) as evidence
> of Pangolin testing."

**Step 6.2 — Spasibo write-back memo walk** (15 секунд)

`docs/SPASIBO-WRITEBACK-DESIGN.md` → §3 verdict table:
- HEDGE → 0.50% cashback (premium rate)
- SWAP → 0.10%
- ADD_LIQ → 0.05%
- CLAIM_FEE → 0.10%
- Caps: 100 баллов/day, 2000/month
- Sprint 7+ implementation после Spasibo BU contract signature

> "R#29 (write-back scope creep) downgrades to 'spec'd backlog'.
> 7.A в Sprint 7 — PO trek по контракту."

---

## Q&A bank

### From CFO

**Q**: «Какой эффект на Q3 revenue от активации protocol fee?»
**A**: ~45M ₽/year run-rate на 22 seed пулах при baseline 50M ₽/день
объёма (revenue research §5 — moved Q3 target с 300-500M до 400-650M).
Реальный uptake зависит от того, сколько пулов admin включит — пока
включаем на тестовом, full rollout Sprint 7+.

**Q**: «MM rebate когда первые выплаты?»
**A**: Sprint 7 — scheduler + tier table + onboarding ship. Первая
выплата 1 MM (контракт уже подписан, 6.A) — sprint close.

### From Compliance

**Q**: «Самозапрет соответствует 115-ФЗ amendment 2024 полностью?»
**A**: Прототип-scope — да. Production требует **реальной ЦБ РФ
verification** при finaliseLift (сейчас stub — period elapsed
автоматически). Sprint 7+ RU-R6-stage2 закроет это.

**Q**: «AML pattern alerts уйдут в Росфинмониторинг автоматически?»
**A**: Sprint 6 — пишем в `aml_alerts` table + log + outbox.
Sprint 7+ Track 3 — notification-service consumer + compliance email
gateway + Sprint 8+ real Росфинмониторинг integration.

### From Treasury BU

**Q**: «Protocol fee на 22 пулах — это нас будет касаться?»
**A**: По solidarity со Sber Treasury LP onboarding (4.A), сначала
включим только на 2-3 retail пулах. Sber Treasury позиции остаются
с `protocolFeePct=0` пока контракт не уточнит. Admin per-pool toggle
даёт точный контроль.

**Q**: «Hedge mass-action — будет «Открыть всё» по preset?»
**A**: Sprint 7 BA backlog. Текущий «Закрыть всё» закрывает end-of-day
flat-the-book usecase; «Открыть всё» открывает рисковый use case
(opening positions без individual review) — нужна compliance дискуссия.

### From prospect corp client

**Q**: «B2B portal frontend когда?»
**A**: Sprint 7 day 1-5. Mockups в руках, FE dev запускается завтра.

**Q**: «Pangolin CI — что это даёт мне как клиенту?»
**A**: Госконтракт-eligibility (Минцифры реестр). Если ваша компания
работает с госструктурами через нас, это снимает один из reg-boxes.

---

## Run-of-show

| Минута | Кто | Что |
|---|---|---|
| 0-1 | PO | Открытие, status page, k6 5.F numbers |
| 1-4 | IT-lead | Protocol fee activation (Act 2) — **revenue headline** |
| 4-7 | Backend lead | Самозапрет lifecycle (Act 3) — **compliance headline** |
| 7-10 | SA | AML pattern detection (Act 4) |
| 10-12 | Frontend lead | Hedge mass-action + margin deep-link (Act 5) |
| 12-14 | SRE + BA | Pangolin CI + Spasibo memo walk (Act 6) |
| 14-25 | All | Q&A |

---

## Sprint 6 demo prep check-list

- [ ] docker-compose up -d (60s wait)
- [ ] All 9 services UP on status page
- [ ] Both admin + ivanov tokens captured in env vars
- [ ] AML demo Postman collection imported
- [ ] Self-restriction lift-cooling-days=0 override prepared
- [ ] GitHub Actions backend.yml run open in tab
- [ ] Spasibo-writeback-design.md open in editor as fallback

---

*Updated: 2026-06-16 SA + PO + IT-lead. Owner of follow-up: PO.
Demo accompanies SPRINT-6-ACCEPTANCE.md.*
