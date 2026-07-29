# Sber DLMM Platform — инструкции для Claude Code

Платформа ликвидности DLMM (Dynamic Liquidity Market Maker) «банковского» уровня:
Java 21 / Spring multi-module + два React-UI. Показывается инвесторам как демо.
Репо `github.com/resgos/sber-dlmm-platform`.

Общие правила работы со мной — в `~/.claude/CLAUDE.md`. Здесь только то, что специфично
для этого репозитория. Факты, история решений и грабли инфраструктуры — в memory-графе
проекта (`MEMORY.md` → отдельные файлы).

## Топология ветвей — прочитай ПЕРЕД работой

Это главный источник путаницы в репо. Состояние на 29.07.2026:

| Ветка | Что в ней | Отставание |
|---|---|---|
| `claude/elated-elgamal-dba521` | **де-факто главная линия платформы**: UI-тесты (46 файлов), vitest, playwright, lint-ratchets, `scripts/redeploy-*.sh` | — |
| `main` | сильно устарел, содержится в elated целиком | −465 коммитов от elated |
| `claude/clever-blackwell` | ветка про load-agent/loadgen (v1.20), отбранчована от старого main | −467 от elated, +38 своих |

Практические следствия:
- **`npm test` / `npm run lint:all` есть только на elated.** Если в дереве их нет — ты не на
  той ветке, а не «тестов в проекте нет». То же для `scripts/redeploy-backends.sh`.
- Новую работу по платформе/UI branch'ить **от `claude/elated-elgamal-dba521`**, не от main
  (это же относится к `/batch`-субагентам — они по умолчанию стартуют со старого baseline).
- Работа по load-agent идёт в своей линии (`clever-blackwell`) и UI-инфраструктуры не требует.
- В репо живут git-воркт-три (`.claude/worktrees/*`) — `git worktree list` показывает, какая
  ветка где раскатана. Не путать основную папку с воркт-ри: файл может «отсутствовать» просто
  потому, что ты смотришь в другое дерево.

## Модули и порты

Maven-реактор (Java 21): `dlmm-common`, `dlmm-user-service`, `dlmm-token-service`,
`dlmm-pool-engine`, `dlmm-transaction-service`, `dlmm-fee-service`, `dlmm-price-oracle`,
`dlmm-notification-service`, `dlmm-admin-bff`, `dlmm-gateway`. UI: `dlmm-user-ui`,
`dlmm-admin-ui` (Vite 5 + React 18, оба со скриптами `dev`/`build`/`preview`).

Docker-стек (`docker/docker-compose.yml`) — хост-порты:

| Сервис | Порт | Сервис | Порт |
|---|---|---|---|
| gateway | 8080 | notification-service | 8087 |
| user-service | 8081 | admin-bff | 8088 |
| token-service | 8082 | admin-ui | 3000 |
| pool-engine | 8083 | **user-ui** | **3001** |
| fee-service | 8084 | postgres | 5432 |
| transaction-service | 8085 | kafka | 9092 |
| price-oracle | 8086 | clickhouse | 8123 / 9000 |

Внутри контейнеров все Java-сервисы слушают 8080 — наружу разведены по 808x.

## Домен

Бины и геометрическая лесенка цен, `activeBinId = 2^23` (8 388 608) у всех пулов
(рабочий диапазон 8388558–8388658), базовая валюта SRUB, инвариант
`liquidity = reserveX·price + reserveY`. Суммы масштабированы ×10⁴: 1 raw = 10⁻⁴ токена,
конвертацию raw↔человеческое делает фронт. Реальные пулы — asset/SRUB (asset = tokenX).

**Эталон UX, фич и математики — Meteora DLMM (edge.meteora.ag): расчёты сверять с ней.**
UI-кит — Plasma (`@salutejs/plasma-tokens-web`, шрифт SB Sans Text), OSS от SberDevices.

## Проверка изменений

- Java: сборка реактора Maven, юнит-тесты рядом с кодом (`*Test.java`).
- UI (на elated): `npm test` (vitest), `npm run lint:all` (ratchets по hex/inline-styles/i18n/
  датам/api-параметрам с baseline-файлами в `scripts/`). Ratchets могут быть **предварительно
  красными** — гейтом считать `tsc` (build) и тесты.
- Живая проверка UI на этой машине — отдельная тема с обширными граблями
  (preview умирает, AntD-контролы не поддаются автоматизации, POST'ы из preview блокируются).
  Перед попыткой прочитай в памяти `local-ui-dev-workflow` и `dlmm-preview-instability`;
  надёжный фолбэк — RTL-харнесс + curl к gateway + `docker exec dlmm-postgres psql`.
- Демо-доступ: `ivanov@example.com` / `Demo1234`.

## Грабли, о которые бьются регулярно

- **Docker-образы `docker-dlmm-*` собраны 29.05.2026 и отстают от исходников** — «эндпоинта
  нет / сервис не поднялся» сначала проверять на этот счёт (память: `dlmm-stale-images-db-password`).
- **Пересоздание стека стирает всё, что деплоилось `docker cp`** в живые контейнеры и может
  сменить `JWT_SECRET` (память: `dlmm-stack-recreate-wipes-hotdeploys`).
- На тест-сервере (plain HTTP, не secure context) `crypto.randomUUID()` недоступен — ломает
  идемпотентность свопа.
- `POST /auth/login` отдаёт 403, если origin не в allowlist.
- Rate limit gateway в старом образе ~10 rps на ключ → нагрузочные сценарии с `maxRps <= 8`.
- Цены пулов не следуют за внешним рынком без оракула — повторяющаяся претензия к демо.

## Правила этого репо

- **Коммиты — в рабочую/авто-ветку, никогда в `main`** (стоит хуком `main-branch-guard`,
  обход только осознанно через `ALLOW_MAIN=1`).
- Дизайн-планка: контраст обязателен, **без эмодзи в UI**, **без hard-coded `#FFFFFF`**
  (ломает тёмную тему) — проверяется хуком `design-guard` при каждой правке UI-файла.
  Осознанный брендовый hex помечать комментарием `brand-hex-ok`.
- Жаргон наружу не выносить: «bin X/Y» → символы токенов пары, «bps» → человеческие проценты.
- Цифры в демо должны быть реалистичными (в сидах встречался TVL в 163 трлн ₽).
- Правка `dlmm-common` = полный redeploy бэкенда — флагать, а не делать молча.
- Нагрузочное тестирование живёт здесь же: `load-agent/` (zero-dep CLI v1.20) + skill
  `/load-test`; тик полировки платформы — skill `/dlmm-tick`.
