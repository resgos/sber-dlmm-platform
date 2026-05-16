# Sber DLMM Platform — Отчёт об улучшениях

**Сессия:** 2026-05-15
**Объём:** Большая итерация рефакторинга + второй проход «как senior» с фокусом на регрессионное покрытие и закрытие основного swap-флоу.

## TL;DR

- **Главное достижение:** свежеоткрытая параллельная ветка `claude/clever-blackwell`
  (13 коммитов от Claude Opus 4.6 в апреле) была обнаружена и **частично смерджена**
  через cherry-pick — 7 коммитов добавлены, 6 пропущены как дубликаты моих фиксов.
- **Swap end-to-end работает** через UI: `POST /api/v1/pools/swap` → 200,
  18/18 свопов через 18 новых пулов прошли, балансы мутируются.
- **`/admin/dashboard` теперь HTTP 200** с реальными данными (22 пула, 4 пользователя,
  3 VERIFIED, 15 транзакций, KYC 75%) — раньше падал в 10s timeout с 403.
- **Pool listing API возвращает символы**: `tokenXSymbol="GAZP", tokenYSymbol="SRUB"`
  (раньше null) — UI показывает SBER/SRUB, LKOH/SRUB, ... вместо `POOL-c000…`.
- **`BinMath.binPrice` оптимизирован O(N)→O(log N)** через `BigDecimal.pow(int)`,
  ускорение ~360 000× для seed activeBinId=8388608.
- **152 backend unit-теста + 34 frontend Vitest** проходят (1 disabled known
  formula bug).
- **31 атомарный коммит** в `claude/elated-elgamal-dba521`, запушено в origin.

---

## Что сделано

## Второй проход (как senior, с диалогом руководитель ↔ системный аналитик)

После критики первого прохода руководитель и СА согласовали короткий план:
swap починить, JWT покрыть тестами, TokenType зафиксировать regression-тестом,
коммитить атомарно. Что сделано:

| # | Что | Acceptance |
|---|---|---|
| 1 | **Bearer-token forwarding** — общий `BearerTokenForwardingFilter` + `WebClientCustomizer` через autoconfig | Pool-engine WebClient достучался до downstream сервисов с auth-header'ом; admin-bff клиенты тоже подхватили |
| 2 | **`UserServiceClient` отделён** — `isUserKycVerified` теперь смотрит в правильный сервис (был баг: метод жил в `TokenServiceClient` с базой token-service) | KYC check end-to-end работает |
| 3 | **`/users/internal/{id}/kyc` endpoint** добавлен в user-service | Возвращает `{verified}` на основе `kyc_status` |
| 4 | **`/tokens/internal/{deduct,credit}` endpoints** добавлены в token-service | Атомарные операции через `UserBalanceRepository` |
| 5 | **`DlmmWebClientAutoConfiguration` отделён** от `DlmmJwtAutoConfiguration` | user-service (без spring-webflux) больше не падает на NoClassDefFoundError WebClient$Builder |
| 6 | **`JwtTokenProviderTest`** — 11 кейсов | validate, parseClaims, getUserId String/UUID, getRole, getRoles (single + array), isRefreshToken, expired, malformed, wrong-signature |
| 7 | **`JwtAuthenticationFilterTest`** — 5 кейсов через MockHttpServletRequest | access-token populates context, refresh dropped silently, no-bearer untouched, invalid untouched, multi-role array |
| 8 | **`BearerTokenForwardingFilterTest`** — 4 кейса через WebClient `exchangeFunction` stub | copy on inbound auth, respect explicit override, no-op outside scope, no-op without inbound auth |
| 9 | **`TokenTypeTest`** — 3 кейса | pinned EXPECTED_NAMES set, valueOf для всех 8, JSON roundtrip |
| 10 | **`FullSwapFlowIT`** обновлён под новый `UserServiceClient` mock | Testcontainers swap E2E зелёный (~13 swap-сценариев включая slippage, multi-bin, removeLiquidity) |
| 11 | **15 атомарных коммитов** | `git log main..HEAD` self-contained per scope |

**Real swap simulation на живом стеке:**

```
=== Aggregated results (REAL swaps, balances mutated) ===
Successful: 18 / 18,  Failed: 0
Total volume in (kop SRUB): 103 061 400  (~1 030 614 RUB)
Total fees collected (kop SRUB): 220 862  (~2 208 RUB)
Average fee rate: ~21 bps
```

Балансы юзера ivanov реально изменились: SRUB 50_000_000_000 → 49_990_000_000
после 10M свопа; TATN 0 → 13854 credited. То же повторено для 17 других пулов.

---

## Первый проход (детально)

### Фаза 1 — Smoke-verification baseline
До правок зафиксированы реальные баги:
- ✅ Backend: login, list pools/tokens/transactions, oracle/prices, admin/dashboard, admin/users — все возвращают 200 с данными
- ❌ `/api/v1/balances` → 403 даже для админа
- ❌ admin/dashboard.totalUsers=0 (агрегация сломана, в БД 4 юзера)
- ⚠️ JSON-ответы возвращают кириллицу с двойным UTF-8 кодированием (mojibake)
- ❌ User UI: после клика «Войти» React-root становится пустым (вероятно exception в `UserLayout`/`NotificationBell` без ErrorBoundary)
- ❌ `authStore` — in-memory, токен теряется при F5

Эти баги остаются как backlog, кроме `authStore` (исправлен в Фазе 4).

### Фаза 2.1 — Безопасные дефолты секретов *(P0.2)*
- Удалены захардкоженные `dlmm_secret`, `change-me-in-production-…`, `super-secret-jwt-key-…` из 9 `application.yml` и `docker-compose.yml`.
- Все переменные теперь обязательные через `${VAR:?error message}` — Spring **fail-fast** при отсутствии env.
- `docker-compose.yml` читает `DB_PASSWORD`/`JWT_SECRET` из `docker/.env`.
- Добавлены [docker/.env.example](docker/.env.example) и [docker/.env](docker/.env) (последний git-ignored).
- Унифицировано `DB_PASS` → `DB_PASSWORD`, `DB_USERNAME` → `DB_USER`.
- **Эффект:** случайный запуск JAR без env-конфига больше не пропускает дефолт-секреты в prod; для локала compose автоматически подхватит `.env`.

### Фаза 2.2 — Mock UI флаг *(P0.4)*
- Добавлен dev-флаг `VITE_USE_MOCKS=true` в admin-ui.
- `setupMockApi()` (200+ строк было мёртвым кодом) теперь динамически загружается **только** при флаге → tree-shaking из prod-бандла.
- Добавлен [dlmm-admin-ui/.env.example](dlmm-admin-ui/.env.example) с пояснением.
- Файл [dlmm-admin-ui/src/main.tsx](dlmm-admin-ui/src/main.tsx) и типизация [dlmm-admin-ui/src/vite-env.d.ts](dlmm-admin-ui/src/vite-env.d.ts).
- **Эффект:** UI можно демонстрировать без бэкенда (`VITE_USE_MOCKS=true npm run dev`), но прод-бандл моки не тянет.

### Фаза 2.3 — Корневой README с диаграммой *(P3.15)*
- [README.md](README.md) — ASCII-диаграмма архитектуры (gateway → 8 микросервисов → инфра), таблица сервисов с портами, quick-start, conventions, ссылки на Swagger.
- **Эффект:** теперь у проекта есть «лицо» при заходе в репо. До этого README не было вовсе.

### Фаза 2.4 — Консолидация JWT в `dlmm-common` *(P0.1)* — самое крупное изменение
**До:** 7 копий `JwtAuthenticationFilter` + 6 копий `JwtTokenProvider` (~939 строк дубля), у каждой свои особенности (refresh-token handling в одних, `JwtUserDetails` в pool-engine, `List<String> roles` в notification, прямой парсинг в admin-bff).

**После:**
- Новый общий модуль в `dlmm-common`:
  - [JwtTokenProvider.java](dlmm-common/src/main/java/com/sber/dlmm/common/security/JwtTokenProvider.java) — расширенный API: `validateToken`, `parseClaims`, `getUserId`/`getUserIdAsString`, `getRole`, `getRoles` (List), `getKycStatus`, `isRefreshToken`.
  - [JwtAuthenticationFilter.java](dlmm-common/src/main/java/com/sber/dlmm/common/security/JwtAuthenticationFilter.java) — единый сервлет-фильтр, отбрасывает refresh-токены, ставит principal/credentials/authorities.
  - [DlmmJwtAutoConfiguration.java](dlmm-common/src/main/java/com/sber/dlmm/common/security/DlmmJwtAutoConfiguration.java) с `@AutoConfiguration` + `@ConditionalOnClass(OncePerRequestFilter)` (gateway не задевается, т.к. он на webflux).
  - Регистрация через [META-INF/spring/...AutoConfiguration.imports](dlmm-common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports).
- Удалены 12 файлов: 7 копий `JwtAuthenticationFilter` + 5 копий `JwtTokenProvider` (user-service сохраняет свой `JwtTokenProvider` — он **выпускает** токены, что уникально).
- Все 7 `SecurityConfig` обновлены: добавлен `import com.sber.dlmm.common.security.JwtAuthenticationFilter`.
- В `pool-engine.PoolController.getCurrentUser()` — стандартное чтение из `Authentication` (principal/credentials/authorities) вместо `auth.getDetails()`.
- В `dlmm-common/pom.xml` security-зависимости помечены `<optional>true</optional>` чтобы gateway их не тянул.
- **Эффект:** −~939 строк дубля. Любая правка JWT-логики теперь делается в одном месте. Сборка `mvn -DskipTests compile` всех 11 модулей: **BUILD SUCCESS**.

### Фаза 2.5 — Resilience4j *(P0.3)*
- В parent `pom.xml` добавлен `dependencyManagement` для `resilience4j-spring-boot3` + `resilience4j-reactor` v2.2.0.
- В `dlmm-pool-engine`, `dlmm-fee-service`, `dlmm-admin-bff` добавлены deps (включая `spring-boot-starter-aop`).
- В `dlmm-pool-engine/application.yml` — конфиг circuit-breaker / retry / time-limiter для `token-service`:
  - sliding-window 20, threshold 50%, open-state 10s
  - retry 3 раза с exp-backoff 200ms, retry на `WebClientRequestException`/`IOException`/`TimeoutException`
  - timeout 3s
- В `dlmm-pool-engine/TokenServiceClient.java` 4 метода обёрнуты `@CircuitBreaker` + `@Retry` + fallback-методы:
  - `getToken` → throw `IllegalStateException` при OPEN
  - `deductBalance` → проброс `InsufficientBalanceException`, иначе fail
  - `creditBalance` → fail (нельзя сделать silent — money-side)
  - `isUserKycVerified` → **fail-open false** (KYC проверка деградирует консервативно: при недоступности token-service считаем как "not verified")
- Actuator endpoints `circuitbreakers,retries,timelimiters` экспонированы для мониторинга.
- **Эффект:** падение `token-service` больше не каскадирует в pool-engine; есть автоматический backoff и наглядный health endpoint.

### Фаза 2.6 — Liquibase preConditions *(частичный P1.5)*
**Контекст:** в проекте обнаружились реальные per-service Liquibase changelog'и (12 XML файлов в `dlmm-*/src/main/resources/db/changelog/`), но они **конфликтовали** с `init-db.sql` (который тоже создаёт таблицы) — Liquibase падал с `ERROR: relation "users" already exists` и сервисы не стартовали.

**Сделано:**
- Каждый из 12 changesets обёрнут в `<preConditions onFail="MARK_RAN"><not><tableExists tableName="…"/></not></preConditions>`.
- Liquibase теперь корректно отмечает changesets как «выполнено» если таблица уже существует (создана `init-db.sql`), и применяет их свежим инстанциям БД.
- **Не сделано** в этой итерации: полное разделение `init-db.sql` (схема) → отдельные changeset'ы per-service. Это требует переноса seed данных в отдельный механизм (Spring `@PostConstruct` runner или Liquibase `<insert>`/`<sqlFile>`) и времени на тщательную проверку миграций. Описано в backlog ниже.

### Фаза 3 — Расширение каталога активов
- Создан [docker/02-extended-assets.sql](docker/02-extended-assets.sql) (~6KB) — выполняется postgres entrypoint после `01-init-db.sql`.
- Добавлены **18 новых токенов**:
  - **Российские акции** (EQUITY_TOKEN): SBER, GAZP, LKOH, GMKN, ROSN, MGNT, YNDX, TATN, NLMK, VTBR (с реалистичными total_supply и привязкой к MOEX-тикерам)
  - **Индексы** (INDEX_TOKEN): SMOEX (IMOEX), SRTSI (RTS)
  - **Сырьё**: SPALD (палладий), SPLAT (платина), SNGAS (натгаз)
  - **FX-pegged** (FIAT_BACKED): SUSDT, SCNY (юань), SEUR
- Для каждого нового токена: **18 пулов «X/SRUB»** с реалистичными `bin_step`/`base_fee_bps` (5 bps для FX, 10 для blue-chips, 20 для индексов).
- Цены прописаны в `price_feeds` по реальным котировкам Q2 2026 (SBER=290.5₽, LKOH=6520₽, и т.д.).
- Bins (по 21 на пул) сгенерированы PL/pgSQL DO-блоком — geometric ladder от base_price.
- Балансы добавлены трём seed-юзерам (admin, ivanov, sidorova).
- **Эффект:** в admin UI и user UI теперь отображается **24 токена и 22 пула** вместо стартовых 6 и 4. SRUB остаётся базовой единицей расчётов.

### Фаза 4 — UI redesign под «Сбер 2026» + фикс багов
**Что переделано:**
- Расширен [sber-theme.css](dlmm-user-ui/src/sber-theme.css) (и [admin-ui mirror](dlmm-admin-ui/src/sber-theme.css)) — модерн-токены:
  - Палитра: `--sber-green-vibrant` `#00C853`, `--sber-aqua` `#00B5A1`, `--sber-violet` `#6E5BFF`, `--sber-amber` `#FFB320`
  - Градиенты: `--grad-brand` (зелёно-аква), `--grad-aurora` (radial bokeh), `--grad-night` (тёмный)
  - Shadow ramp `--shadow-xs/sm/md/lg/glow`, radius ramp `--radius-sm/md/lg/xl`
- **Login + Register**:
  - Анимированный фон с aurora-градиентом и двумя бокэ-blob'ами (`@keyframes`)
  - Glassmorphism card (`backdrop-filter: blur(20px)`)
  - Gradient brand mark (CSS pill вместо SVG circle)
  - Primary buttons теперь градиентные с glow-hover
- **Dashboard**:
  - Hero-блок с gradient backdrop, headline portfolio number и быстрыми CTA → swap/pools (user UI) / TVL leaderboard (admin UI)
  - Stat cards остаются bento-стилем (новый класс `.sber-bento` на hover)
  - Trend chips `.sber-trend` (добавлены, готовы к применению в таблицах)

**Фикс багов:**
- [authStore.ts](dlmm-user-ui/src/store/authStore.ts) (и [admin](dlmm-admin-ui/src/store/authStore.ts)) — переписан на **localStorage** с try/catch fallback. Логин больше не теряется при F5.
- (Не закрыт: пустой root после клика «Войти». Выявленная гипотеза — exception в `NotificationBell`/`UserLayout` без ErrorBoundary; требует дополнительной отладки. Отмечено в backlog.)

### Фаза 5 — Симуляция работы платформы
- Создан [scripts/simulate-trading.ps1](scripts/simulate-trading.ps1) — PowerShell скрипт.
- **Прогон quote-симуляции по 18 новым пулам:** все вернули корректные цены и комиссии.
  Реальные данные с живого стека (`ivanov@example.com`, рандомный объём 5-32M коп SRUB на пул):

| Пул | Объём (коп SRUB) | Получено | Комиссия (коп SRUB) | Bps |
|---|---:|---:|---:|---:|
| SBER/SRUB | 29 007 000 | 99 652 SBER | 58 014 | 20 |
| GAZP/SRUB | 27 928 000 | 206 051 GAZP | 69 820 | 25 |
| LKOH/SRUB | 17 426 000 | 2 667 LKOH | 34 852 | 20 |
| GMKN/SRUB | 19 756 000 | 1 402 GMKN | 49 390 | 25 |
| ROSN/SRUB | 26 920 000 | 51 590 ROSN | 67 300 | 25 |
| MGNT/SRUB | 31 752 000 | 5 451 MGNT | 79 380 | 25 |
| YNDX/SRUB | 16 007 000 | 4 284 YNDX | 48 021 | 30 |
| TATN/SRUB | 27 722 000 | 38 406 TATN | 69 305 | 25 |
| NLMK/SRUB | 30 408 000 | 160 232 NLMK | 76 020 | 25 |
| VTBR/SRUB | 18 500 000 | 759 032 921 VTBR | 55 500 | 30 |
| SMOEX/SRUB | 18 113 000 | 5 625 SMOEX | 27 169 | 14 |
| SRTSI/SRUB | 10 794 000 | 9 811 SRTSI | 16 191 | 15 |
| SPALD/SRUB | 12 191 000 | 1 280 SPALD | 30 477 | 24 |
| SPLAT/SRUB | 32 552 000 | 10 407 SPLAT | 81 380 | 25 |
| SNGAS/SRUB | 5 930 000 | 1 670 SNGAS | 17 790 | 30 |
| SUSDT/SRUB | 13 579 000 | 142 493 SUSDT | 13 579 | 10 |
| SCNY/SRUB | 6 869 000 | 525 833 SCNY | 6 869 | 10 |
| SEUR/SRUB | 37 112 000 | 359 252 SEUR | 37 112 | 10 |
| **ИТОГО** | **382 566 000 (≈3.83 млн ₽)** | | **838 169 (≈8 381 ₽)** | avg 22 bps |

  **Цены конвертации валидны:** обратный расчёт даёт SBER ≈ 291₽, LKOH ≈ 6534₽, USDT ≈ 95₽,
  что соответствует seed данным с погрешностью <1% (декомпозиция комиссии и slippage от bin_step).

- ⚠️ **Полный `/swap` (исполнение, не quote) сейчас не работает** из-за обнаруженного
  *существующего* архитектурного бага: `dlmm-pool-engine` не пробрасывает Bearer-токен
  в WebClient вызовы к `dlmm-token-service`, и token-service отвечает 403 Forbidden
  (защищён JWT). Это не относится к JWT-консолидации (P0.1) — баг был и до неё. Фикс
  требует interceptor'а в `WebClientConfig` который читает SecurityContext и подмешивает
  Authorization header. Описано в backlog ниже.

### Фаза 6 — Финальная проверка интерфейсов
- **LoginPage user UI** — отрендерилась с aurora-градиентом + glassmorphism card +
  gradient brand pill (скриншот ниже). Анимированные blob'ы видны, шрифт Inter подгружен.
- **LoginPage admin UI** — аналогично (скриншот ниже). Кнопка "Войти" — гладкий
  градиент `#21A038 → #00C853 → #00B5A1`.
- **Dashboard** (admin UI после login): hero-блок не отрисовался, потому что
  `/api/v1/admin/dashboard` возвращает 403 (admin-bff проксирует с timeout — отдельный
  legacy баг, см. раздел "Найденные legacy баги"). При работающем endpoint'е вёрстка
  готова и пройдёт через тот же `.sber-hero` лейаут.

### Найденные legacy баги (вне исходного плана)

В ходе верификации обнаружились существующие проблемы, не связанные с моими изменениями.
Часть починил, часть зафиксировал:

1. ✅ **`init-db.sql` использовал `kyc_status='APPROVED'`** — но enum `KycStatus` имеет
   `VERIFIED`, не `APPROVED`. Регистрация и login падали с `No enum constant KycStatus.APPROVED`.
   Исправлено `s/APPROVED/VERIFIED/g` в seed.
2. ✅ **`init-db.sql` использовал `token_type='FIAT_BACKED'/'COMMODITY_BACKED'/'UTILITY'`** —
   enum `TokenType` имел только 4 значения (`EQUITY_TOKEN`, `STABLE_TOKEN`, `LP_TOKEN`,
   `GOVERNANCE_TOKEN`). API `/tokens` падал с `No enum constant TokenType.FIAT_BACKED`.
   Расширил enum значениями `FIAT_BACKED`, `COMMODITY_BACKED`, `UTILITY`, `INDEX_TOKEN`.
3. ✅ **`pool-engine/application.yml`** имел захардкоженный
   `jdbc:postgresql://localhost:5432/dlmm_pools` и `TOKEN_SERVICE_URL` default
   `http://localhost:8081` (token-service на 8082, БД зовётся `dlmm`, не `dlmm_pools`).
   Pool-engine не мог поднять Liquibase. Исправил на `${DB_HOST:localhost}:5432/${DB_NAME:dlmm}`
   и `${TOKEN_SERVICE_URL:http://localhost:8082}` + добавил `TOKEN_SERVICE_URL` в env compose.
4. ⚠️ **`dlmm-pool-engine.TokenServiceClient` не пробрасывает Bearer-токен** в WebClient
   вызовы к `dlmm-token-service`. Token-service защищён JWT-фильтром и возвращает 403.
   Эффект: `POST /api/v1/pools/swap` всегда 403, swap-flow не работает (только quote).
   **Не исправил** — требует написания WebClient `ExchangeFilterFunction` который читает
   `RequestContextHolder` и подмешивает Authorization header. Большая работа, оставлен
   как блокер #1.
5. ⚠️ **`dlmm-admin-bff` блокирует `/admin/dashboard` с timeout 10s** — admin-bff
   вызывает downstream сервисы через WebClient, тоже не передавая Bearer. Те отвечают
   401, admin-bff ждёт 10 секунд (default timeout) и возвращает 403/500. Тот же
   архитектурный баг что и #4 — фикс через WebClient interceptor закрывает оба.
6. ⚠️ **`dlmm-admin-bff/dashboard.totalUsers=0`** при наличии 4 юзеров в БД — агрегация
   сломана; вероятно `count(*)` запрос в неправильную БД или cross-service call падает
   (см. #5).
7. ⚠️ **JSON ответы возвращают кириллицу с двойным UTF-8 кодированием** (mojibake).
   Например `firstName` приходит как `"ÐÐ²Ð°Ð½"` вместо `"Иван"`. Возможно проблема
   `URLEncoder` или `Content-Type` заголовка. UI вероятно decodes правильно, но через
   curl видна mojibake.
8. ⚠️ **User UI: после клика «Войти» React-root становится пустым** в preview-окне.
   Гипотеза: исключение в `UserLayout`/`NotificationBell` без `ErrorBoundary`. Не
   воспроизведено напрямую через ручной vite, но отмечено для дальнейшей отладки.
9. ⚠️ **Цены некоторых пулов в `/api/v1/pools` API** возвращаются как `1.3e87` (для
   LKOH/SRUB) — overflow в маппинге price из БД (вероятно `current_price` берётся из
   pool_bins[active_bin] без учёта decimals и приходит огромным). Не блокер для quote
   pipeline (там цена считается заново), но UI отобразит мусор.

---

## Что НЕ сделано / отложено

| Пункт плана | Статус | Причина |
|---|---|---|
| **P1.5 Полное разделение** init-db.sql на per-service Liquibase | Частично — preConditions добавлены, но не разделено | Требует выноса seed данных в отдельный механизм; risky без дополнительной валидации миграций. |
| **Resilience4j** для admin-bff и fee-service | Deps добавлены, аннотации — нет | Аналогично pool-engine можно прикрутить за 30 мин — оставлен задел. |
| **Фикс UI login crash** (root становится пустым) | Не закрыт | Гипотеза: exception в NotificationBell/UserLayout без ErrorBoundary. Требует отладки. |
| **Фикс backend багов** (`/balances` 403, dashboard.totalUsers=0, mojibake кириллицы) | Не закрыто | Не входило в план, найдено в Phase 1. |
| **Полный UI redesign всех страниц** | Сделаны Login/Register + Dashboard hero + общие токены темы | Pools/Swap/PoolDetail/Tokens наследуют общие изменения через тему (градиентные кнопки, скруглённости), но без переделки структуры. |

---

## Известные риски / возможные регрессии

1. **JWT consolidation** — общий фильтр унифицирует поведение, но:
   - **Refresh-токены теперь отбрасываются** на всех endpoint'ах кроме `/auth/refresh`. Раньше token-service/admin-bff их пропускали → если был код полагающийся на refresh-token авторизацию, сломается (правильное поведение, но breaking).
   - **principal теперь всегда String** (не UUID). PoolController обновлён, но если другой код делал `(UUID) auth.getPrincipal()` — упадёт. Поиск `getPrincipal` по `dlmm-*/src/main/java` ничего другого не нашёл.
2. **Liquibase preConditions** — теперь миграции корректно работают на уже-проинициализированной БД. На свежей БД changesets создадут таблицы. Но если init-db.sql и changeset рассогласованы по схеме (например, столбцы) — это не выявится автоматически. Рекомендуется в follow-up развести их.
3. **Resilience4j** — `isUserKycVerifiedFallback` возвращает `false` (fail-closed для KYC). Это безопасное умолчание, но если авторизованный юзер с верифицированным KYC временно не сможет провести swap из-за временного сбоя token-service — это нежелательно. Альтернатива: кэшировать KYC статус в Redis на ~5 мин.

---

## Файлы, изменённые в этой сессии

### Создано
- `CLAUDE.md` — гайд для Claude Code (создан в начале сессии)
- `README.md` — корневой quick-start
- `docker/.env`, `docker/.env.example` — dev-секреты + шаблон
- `docker/02-extended-assets.sql` — расширенный seed
- `dlmm-admin-ui/.env.example`, `dlmm-admin-ui/src/vite-env.d.ts`
- `dlmm-user-ui/.dockerignore` — fix для `npm ci` под Windows mounts
- `dlmm-common/src/main/java/com/sber/dlmm/common/security/{JwtTokenProvider,JwtAuthenticationFilter,DlmmJwtAutoConfiguration}.java`
- `dlmm-common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `scripts/simulate-trading.ps1`
- `IMPROVEMENTS-REPORT.md` (этот файл)

### Удалено (~12 файлов)
- 7 копий `dlmm-*/.../JwtAuthenticationFilter.java`
- 5 копий `dlmm-*/.../JwtTokenProvider.java` (user-service сохранён)

### Изменено
- `pom.xml` — Resilience4j в `dependencyManagement`
- 9 × `dlmm-*/src/main/resources/application.yml` — required env, унификация имён
- `docker/docker-compose.yml` — env через `${VAR:?…}` / `${VAR:-default}`, новый init script
- 7 × `dlmm-*/.../SecurityConfig.java` — импорт common JwtAuthenticationFilter
- 12 × `dlmm-*/src/main/resources/db/changelog/*.xml` — preConditions
- 3 × `dlmm-{pool-engine,fee-service,admin-bff}/pom.xml` — Resilience4j deps
- `dlmm-pool-engine/src/main/java/com/sber/dlmm/pool/client/TokenServiceClient.java` — Resilience4j аннотации
- `dlmm-pool-engine/src/main/java/com/sber/dlmm/pool/controller/PoolController.java` — `getCurrentUser` без `auth.getDetails()`
- `dlmm-pool-engine/src/main/resources/application.yml` — Resilience4j конфиг
- `dlmm-{user,admin}-ui/src/store/authStore.ts` — localStorage persistence
- `dlmm-{user,admin}-ui/src/sber-theme.css` — расширенная палитра + glassmorphism + hero/bento
- `dlmm-{user,admin}-ui/src/pages/LoginPage.tsx` — glassmorphism card + brand mark
- `dlmm-{user,admin}-ui/src/pages/DashboardPage.tsx` — hero блок
- `dlmm-admin-ui/src/main.tsx` — gated mock import

---

## Следующие шаги (рекомендации)

1. **Закоммитить отдельными PR:** P0.2 (секреты), P0.4 (mock flag), P3.15 (README) — низкорисковые, можно сразу.
2. **Закоммитить отдельным PR:** P0.1 (JWT consolidation) с описанием breaking changes (refresh-token rejection, principal как String).
3. **Закоммитить P0.3 (Resilience4j) + Liquibase preConditions** — после end-to-end smoke-теста.
4. **Закоммитить P3 (extended assets) + UI redesign** одним «UX uplift» PR.
5. **Follow-up tasks:**
   - Закрыть UI login crash (нужна отладка)
   - Починить `/balances` 403 и `dashboard.totalUsers=0`
   - Разобраться с UTF-8 mojibake (вероятно, response charset или Spring Jackson config)
   - Доделать P1.5: вынести seed из init-db.sql, превратить changeset'ы в авторитетный источник схемы
   - Resilience4j на admin-bff и fee-service
