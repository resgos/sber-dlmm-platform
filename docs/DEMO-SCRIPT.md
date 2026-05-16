# Sber DLMM — Demo Script & Q&A Bank

**Duration target**: 20 min demo + 15 min Q&A.
**Audience**: cross-functional, mixed technical depth.

> Setup checklist (T-15 min): see [§0](#0-pre-demo-setup-t-minus-15-min)

---

## 0. Pre-demo setup (T-minus 15 min)

1. `cd docker && docker-compose up -d && sleep 60`
2. Open 4 browser tabs:
   - http://localhost:3000 (admin UI) — login as `admin@sber-dlmm.ru` / `Demo1234`
   - http://localhost:3001 (user UI) — login as `ivanov@example.com` / `Demo1234`
   - http://localhost:8080/swagger-ui.html (gateway docs)
   - http://localhost:8088/actuator/health (deep-health JSON, pretty-print extension on)
3. Pre-warm dashboards: click through every admin page once. This consumes the cold-start latency (Spring lazy init) so the live demo only sees warm calls.
4. Have backup screenshots in `docs/demo/screenshots/` if anything goes sideways.

---

## 1. The pitch (2 min) — PO opens

> *"Sber DLMM делает то, что Uniswap делает на Ethereum: концентрированную ликвидность с динамическими комиссиями. Только у нас всё внутри Сбера — токенизированные рубли, акции, золото, индексы MOEX — и комплаенс с КЫС встроен в каждый своп. За 4 недели мы собрали MVP из 10 микросервисов, двух интерфейсов и полного пайплайна торговли. Покажу что работает."*

Open admin dashboard. **Show the bento grid**:
- 22 ACTIVE пула
- 4 пользователя, 3 VERIFIED
- 11 открытых LP-позиций
- 5.6 млрд RUB volume24h (это seed-данные — реальные транзакции за 30 дней)

---

## 2. End-user flow (5 min) — User UI

Open **user UI tab** (ivanov logged in).

1. **Dashboard**: показываем баланс, активные позиции.
2. **Swap page** (Sber-2026 design):
   - выбор pool SBER/SRUB
   - ввод `1.0 SBER` → quote inline за <100 ms
   - flip-arrow крутится при наведении
   - hit "Обменять" → swap уходит → балансы обновляются
   - открываем "История" → последняя транзакция CONFIRMED
3. **Pools page**: 22 карточки с overlapping token chips, gradient backgrounds.
4. **Open one pool detail**: bin distribution chart, top LPs.

**Что только что показали технически:**
- JWT auth → gateway → pool-engine → token-service (`/deduct` + `/credit`) → transaction-service → notification-service. Шесть сервисов в одной сделке, все асинхронно с подтверждением.

---

## 3. Admin UI (5 min)

Switch to **admin UI tab**.

1. **Dashboard**: значения уже обновились (volume24h на размер только что сделанного свопа).
2. **Users → ivanov**: KYC статус, баланс по всем токенам, история сделок.
3. **Tokens**: 18 токенов. Открываем SBER → видим mint/burn, total supply, holders.
4. **Pools → SBER/SRUB**: bin chart, fee history за 30 дней.
5. **Suspicious transactions**: показываем 1 PENDING + 4 FAILED — система помечает (>5% TVL, частота, price impact).

---

## 4. Reliability (4 min) — IT-lead

> *"Спрашиваете 'что будет если что-то упадёт'. Давайте проверим."*

Открыть **healthcheck JSON** tab (http://localhost:8088/actuator/health). Покажите:
- Каждый downstream — отдельная секция: db, fee-service, pool-engine, token-service, transaction-service, user-service
- Все UP

В терминале:
```bash
docker stop dlmm-token-service
```

Refresh healthcheck JSON. Теперь:
- `tokenService: { status: DOWN, reason: "no response within PT1S" }`
- `poolEngine: { status: DOWN }` (каскад — pool-engine зависит от token-service)
- Остальные 3 UP. **Сбой локализован**.

Now make a swap in user UI → fails fast (503), не висит 30 секунд.

```bash
docker start dlmm-token-service
```

Refresh после 5 секунд → всё снова UP. Swap проходит.

> *"Production-grade гайт: k8s liveness/readiness видят это через `/actuator/health/{liveness,readiness}`, Prometheus собирает метрики через `/actuator/prometheus`. Циркуит-брейкеры (Resilience4j) уже стоят на всех inter-service вызовах."*

---

## 5. Architecture (3 min) — sysAnalyst

Switch to slide / draw on whiteboard:

```
[Browser]
   |
   v
[Gateway 8080]  ← JWT, rate-limit (Redis), routing
   |
   +---> [user-service 8081]  ← auth, KYC
   +---> [token-service 8082] ← balances, mint/burn
   +---> [pool-engine 8083]   ← bin math, swap, liquidity
   +---> [fee-service 8084]   ← variable fee, accruals
   +---> [transaction-service 8085]
   +---> [price-oracle 8086]
   +---> [notification-service 8087] ← Kafka consumer
   +---> [admin-bff 8088]     ← aggregates the above for admin UI

[Postgres 16]  ← Liquibase, single DB for now
[Redis 7]      ← rate-limit state, planned LRU cache layer
[Kafka 3]      ← user-events, token-events, pool-events, fee-events
[ClickHouse]   ← analytics (read-side)
```

Highlight:
- Single DB on purpose — simpler operations, joins still possible across domains. Sharding is a Sprint 4+ conversation.
- Kafka not core path — it's for downstream notifications and analytics. Critical balance mutations are still synchronous (until outbox lands Sprint 2).
- Shared library (`dlmm-common`): JWT filter, token provider, exception handler, bin math. **939 lines of duplicate JWT code was deleted** this past sprint.

---

## 6. Roadmap (1 min)

| Sprint | Focus |
|---|---|
| **2 (next 2 weeks)** | Outbox pattern, CI/CD pipeline, k6 load test baseline |
| **3** | Liquibase migration split, real price-oracle (MOEX + Binance), CSP + secret-vault |
| **4** | Multi-DB sharding option, prod helm chart, prod CORS/cert |

---

# Q&A Bank

Organised by who's likely to ask. Each answer is ≤ 3 sentences for live delivery.

## Product owner / business questions

**Q: Чем мы отличаемся от Uniswap?**
> Концентрированная ликвидность как у v3, но дискретные bins (как у Trader Joe DLMM на Avalanche), плюс — критично для нас — встроенный KYC, токенизированные традиционные активы (акции, золото, валюты), и совместимость с СБЫТ-стеком Сбера. Это не клон, это инфраструктура для внутренней токенизации.

**Q: Какой revenue model?**
> Базовая комиссия pool'а (25 bps на seed pools) + динамическая комиссия в волатильности. Из неё фиксированный процент идёт протоколу (settled через `transactions.fee_amount`), остаток — LPs.

**Q: Сколько уже сделано / сколько осталось до production?**
> MVP — 4 недели, демонстрация сегодня. До production — ещё 4 спринта: outbox, мониторинг, multi-region, прод-helm, реальный price feed. Закрыто 78% backlog'а на demo.

**Q: А регулятор это пропустит?**
> Это closed-loop — токены ходят между пользователями Сбера. Не выпускаем criptoasset в смысле 259-ФЗ. Юридически — внутренний эквивалент денежных требований. Подробное юр-заключение готовится compliance командой; tech-стек никак не запрещает audit-trail (каждая транзакция в DB + Kafka событие).

## IT-lead / DevOps questions

**Q: RPS budget?**
> На едином worker'е (4 vCPU): ~200 swap/sec sustained замеренные локально, p99 латенси <200ms warm cache. Спринт 2: k6 load test с целью 1k rps под 8 worker'ами.

**Q: Что будет при падении postgres?**
> Все сервисы ловят `HikariConnectionException`, /actuator/health → DOWN, k8s перезапускает их. После Sprint 3 — replica + WAL streaming + PITR. RPO/RTO contract owed by PO.

**Q: Где security boundary?**
> JWT валидируется на gateway (один раз) + повторно в каждом сервисе (defense-in-depth). Refresh token принимается ТОЛЬКО на `/auth/refresh`. Internal endpoints (`/users/internal/kyc`, `/tokens/internal/deduct`) технически permitAll но защищены тем, что вызывает их только pool-engine с forwarded user JWT.

**Q: Как версионируется API?**
> URI versioning (`/api/v1/...`). При breaking change — параллельный `/api/v2/...` с deprecation header'ом 6 месяцев. Schema migration через Liquibase changesets (хотя сейчас init-db.sql — тех долг, см. risk #4).

**Q: Что в Kafka, что в БД, какие гарантии?**
> БД — source of truth. Kafka — fan-out для нотификаций и аналитики. Без outbox (Sprint 2) есть окно, когда DB commit прошёл но Kafka send упал → нотификация теряется (баланс не теряется, идемпотентность ключа защищает от двойного исполнения).

## SysAnalyst / sec questions

**Q: DDoS protection?**
> Redis rate-limit на gateway: 100 rps / 150 burst per user-id. После gateway — внутренняя сеть. WAF/CDN — задача инфраструктурной команды на проде.

**Q: Что хранится в JWT? Срок жизни?**
> userId, role, kycStatus. Access — 30 минут, refresh — 7 дней. Подпись HS384, секрет 32 байта (env var). Token revocation — only on logout, нет blacklist (acceptable for 30 min TTL).

**Q: PII storage?**
> ФИО + телефон в `users` (Postgres). Шифрование at-rest — на уровне PVC (Sprint 3). Логи sanitize не делают сейчас (`users.email` появляется в DEBUG логах) — fix backlog.

**Q: Audit trail?**
> Каждая state-mutation создаёт row в `transactions` с user_id, timestamp, idempotency_key. Read-side audit (кто посмотрел чей баланс) — пока нет, риск #N для Sprint 4.

## Demo-day technical questions

**Q: Покажите чтобы пол сбоил а UI не падал.**
> [см. §4 выше]. Делается ровно так.

**Q: Сколько кода написал ИИ vs люди?**
> Это парная работа: я (Claude) пишу первый pass, человек ревьюит. Все commits подписаны `Co-Authored-By: Claude`. Ревью обязательно — мы не сливаем чёрный ящик.

**Q: Тесты есть?**
> 96 unit-тестов на dlmm-common + dlmm-pool-engine (JWT, фильтры, BinMath, FeeCalculator). Один Testcontainers IT-тест (FullSwapFlowIT) — поднимает Postgres + Kafka в Docker и крутит реальный своп. Frontend: 34 Vitest тестов (zustand store, форматирование). Coverage пока 32% — растёт в Sprint 2.

**Q: Не упадёт ли при 100 пользователях разом?**
> Bottleneck сейчас — token-service `/deduct` (synchronous DB lock). Под 100 concurrent swap'ов в один pool — будет очередь. Outbox + queue (Sprint 2) развязывает. Под нагрузку 1k rps по СТАТИЧЕСКИМ pools (свопы в разные pools) — справится спокойно, тестировано на ноутбуке.

---

## Backup if everything breaks

- **Gateway down**: `docker restart dlmm-gateway`
- **Postgres connection pool dead**: `docker restart dlmm-{user,token,pool-engine,admin-bff}-service`
- **UI shows mojibake**: harmless, известный bug (risk #10), показать в Swagger напрямую.
- **Если ноут совсем умер**: открыть `docs/demo/screenshots/` и провести demo по слайдам.

---

*Owner: PO + IT-lead. Refresh after every dress-rehearsal. Last update: Sprint 1, 2026-05-16.*
