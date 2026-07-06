# load-agent — ИИ-агент нагрузочного тестирования REST API

Универсальный (не привязанный к DLMM) инструмент + промпт агента, чтобы любой член команды
мог сказать ИИ-ассистенту «прогони нагрузочный тест на сервис X» и получить честный отчёт.

**Ключевая идея: думает инструмент, а не модель.** Агент рассчитан на слабые модели
(Haiku, GigaChat, локальные 7-14B): вся математика, валидация конфига, вердикт PASS/FAIL и
диагностика зашиты в детерминированный CLI без зависимостей. Модели остаётся выполнить
6 шагов конвейера и дословно пересказать блок «ИТОГ».

## loadgen за 30 секунд

**Конвейер:** `probe → (profile) → init → validate → run --smoke → run` → (`compare`/`merge` для CI/сетки).
Быстрый старт с готового пресета: `node bin/loadgen.mjs init --preset ci --out my.json`.

| Команда | Что делает | Exit |
|---|---|---|
| `probe <baseUrl> [пути…]` | цель жива? какие пути отвечают (пути без ведущего `/`) | 0 / 3 |
| `init [--preset smoke\|browse\|journey\|stress\|ci\|write]` | шаблон или готовый пресет-сценарий | 0 / 1 |
| `validate <scn.json>` | точные ошибки конфига (`✗ …`) | 0 / 1 |
| `profile <log\|csv\|json>` | черновик сценария из статистики хитов | 0 / 1 |
| `run <scn.json>` | нагрузка + вердикт PASS/FAIL | 0 PASS · 1 конфиг · 2 FAIL/регресс · 3 недоступна |
| `compare <base> <cur>` | регресс p95/p99/ошибок/RPS (сумм. и по запросу) | 0 · 2 REGRESSED |
| `merge <r1> <r2> …` | агрегат прогонов с N машин (сумм. RPS + перцентили из гистограмм) | 0 / 2 |

Ключевые флаги `run`: `--smoke --vus N --duration N --workers N --out FILE --baseline FILE --junit FILE --md FILE --html FILE --allow-writes --confirm-external --quiet`.
Секреты — из env: любую строку сценария можно взять как `${VAR}` / `${VAR:-дефолт}`.

**Поля сценария** (подробно — в разделах ниже): `baseUrl` · `auth` (none/bearer/login) · `vars`
(с сервера/список/файл) · `requests[]` (смесь по weight) **или** `flows[]` (цепочки с capture) ·
`load` (vus/durationSec/rampUpSec/thinkTimeMs/maxRps/workers/warmupSec/**stages**) · `thresholds`
(p95Ms/p99Ms/errorRatePct/rpsMin/perRequest) · `monitor` (docker/prometheus) · `setup`/`teardown`
(write-тесты) · `bodyType` (json/form/multipart) · `checks` (валидация ответов).

## Оглавление

**Начать.** [Состав](#состав) · [Быстрый старт руками](#быстрый-старт-руками-без-ии) ·
[Конвейер агента](#конвейер-агента) · [Почему работает на слабых моделях](#почему-это-работает-на-слабых-моделях)

**Писать сценарий.** [Справочник полей](#сценарий-справочник-полей) ·
[Цепочки (flows)](#сценарии-цепочки-flows--user-journeys) ·
[Не-JSON тела (bodyType)](#тела-не-json-form-urlencoded-и-multipart-bodytype) ·
[Секреты `${VAR}`](#секреты-из-окружения-var--сценарий-без-паролей-в-файле) ·
[Профиль нагрузки: stages](#многоступенчатый-профиль-loadstages--ramp--spike--soak) ·
[setup / teardown](#жизненный-цикл-setup-и-teardown-безопасные-write-тесты)

**Гнать и читать отчёт.** [Что умеет отчёт](#что-умеет-отчёт) ·
[TTFB vs скачивание](#разбивка-латентности-ttfb-vs-скачивание-тела) ·
[Кто узкое место (monitor)](#метрики-цели-генератор-или-сервис--кто-узкое-место-monitor) ·
[Параллелизм и ресурсы](#параллелизм-и-ресурсы-генератора)

**СУБД и конвейеры.** [Нагрузка на Postgres/Ignite (`kind:"sql"`)](#нагрузка-на-субд-напрямую-kindsql--postgresignite) ·
[Сквозной лаг Kafka→PG (`kind:"pipeline"`)](#сквозной-лаг-конвейера-kindpipeline--kafkapgignite)

**CI и масштаб.** [Сравнение прогонов (compare)](#регрессии-сравнение-прогонов-compare--ci-гейт) ·
[Распределённый прогон (merge)](#распределённый-прогон-с-n-машин-merge) ·
[Профиль из статистики (profile)](#профиль-нагрузки-из-статистики-profile)

**Прочее.** [Интеграция в код](#интеграция-в-код-nodejs--python--java--scala) ·
[Тесты движка](#тесты-самого-движка) · [Предохранители](#предохранители-важно-для-командного-использования)

## Состав

```
load-agent/
├── bin/loadgen.mjs          # CLI-генератор нагрузки (Node >= 18, ноль зависимостей)
├── scenarios/               # готовые сценарии (*.json)
│   ├── dlmm-read-heavy.json # демо: read-heavy браузинг DLMM через gateway :8080
│   └── dlmm-journey.json    # демо: сценарий-цепочка (list→open→bins) с capture
├── presets/                 # готовые сценарии: init --preset smoke|browse|journey|stress|ci|write
├── clients/                 # обёртки для интеграции в тесты: Node/Python/Java/Scala
├── test/                    # регрессионные self-тесты ядра (node --test)
├── agent/
│   ├── SYSTEM_PROMPT_BODY.md   # переносимый промпт агента (для любого харнесса)
│   ├── REQUEST_ANALYSIS.md     # развёрнутый промпт разбора запроса пользователя → сценарий
│   ├── PROFILE_FROM_STATS.md   # промпт: профиль нагрузки из access-лога/метрик
│   └── README.md               # как подключить к GigaChat/другому LLM
└── results/                 # сюда пишутся JSON-результаты прогонов

.claude/agents/load-tester.md   # готовый субагент для Claude Code (model: haiku)
.claude/skills/load-test/       # скилл-обёртка: /load-test
```

## Быстрый старт (руками, без ИИ)

```bash
node load-agent/bin/loadgen.mjs probe http://localhost:8080 actuator/health api/v1/pools
node load-agent/bin/loadgen.mjs init --out my.json          # шаблон с подсказками
node load-agent/bin/loadgen.mjs validate my.json            # точные ошибки, если что-то не так
node load-agent/bin/loadgen.mjs run my.json --smoke         # каждый запрос по 1 разу (проверка конфига)
node load-agent/bin/loadgen.mjs run my.json                 # нагрузка + вердикт PASS/FAIL
node load-agent/bin/loadgen.mjs run my.json --workers 4     # та же нагрузка на 4 ядрах
node load-agent/bin/loadgen.mjs run my.json --baseline base.json  # + сравнить с эталоном (регресс → exit 2)
node load-agent/bin/loadgen.mjs compare base.json cur.json  # сравнить два прогона
node load-agent/bin/loadgen.mjs profile access.log --base-url http://x  # черновик сценария из лога
```

В Claude Code: `/load-test http://localhost:8080 ...` или попросить текстом
«прогони нагрузочный тест...» — сработает субагент `load-tester`.

## Конвейер агента

```mermaid
flowchart LR
    A[Запрос пользователя] --> B[probe: цель жива?]
    B -- UNREACHABLE --> Z[Стоп: отчёт о недоступности]
    B -- REACHABLE --> P{есть access-лог<br/>или метрики?}
    P -- да --> P2[profile: черновик<br/>сценария из статистики] --> C
    P -- нет --> C[init / готовый пресет]
    C --> D[validate]
    D -- ✗ ошибки --> C2[править JSON по подсказкам] --> D
    D -- OK --> E[run --smoke: каждый запрос 1 раз]
    E -- FAIL --> C2
    E -- все OK --> F[run: полная нагрузка]
    F --> G[Отчёт: дословный ИТОГ + подсказки<br/>+ out/html/junit/md]
    G -. CI-гейт .-> H[compare с baseline]
    G -. прогон с N машин .-> M[merge результатов]
```

Ветки `profile`, `compare`, `merge` — опциональные (профиль когда есть статистика реального
трафика; compare/merge — для CI и распределённого прогона). Ядро — прямая линия
`probe → init → validate → smoke → run`.

## Сценарий: справочник полей

```jsonc
{
  "name": "my-test",
  "baseUrl": "http://localhost:8080",     // только схема://хост:порт
  "timeoutMs": 10000,
  "headers": {},                           // общие заголовки
  "auth": {
    "type": "login",                       // none | bearer | login
    "token": "...",                        // для bearer
    "login": {                             // для login: логин выполняется на каждого VU
      "path": "/api/v1/auth/login",
      "method": "POST",
      "body": { "email": "...", "password": "..." },
      "tokenField": "accessToken"          // dot-путь до JWT в ответе, напр. "data.token"
    }
  },
  "vars": {                                // переменные для {{placeholder}} — 4 источника:
    "poolId": { "setupPath": "/api/v1/pools?size=20", "extract": "content[*].id" }, // с сервера
    "page":   [0, 1, 2],                                          // статический список
    "userId": { "file": "data/user-ids.txt" },                    // файл: .txt построчно (# — комментарий)
    "email":  { "file": "data/users.csv", "column": "email" }     // .csv по колонке; .json — с "extract"
  },
  "requests": [                            // weight = относительная частота
    { "name": "list",   "method": "GET", "path": "/api/v1/pools?page={{page}}", "weight": 5,
      "checks": {                          // валидация ответов (все ключи опциональны)
        "status": [200],                   //   допустимые коды (иначе 2xx-3xx)
        "notEmpty": true,                  //   тело не пустое
        "bodyContains": "content",         //   подстрока в теле
        "jsonPath": "content[*].id",       //   путь обязан дать значения
        "jsonPathEquals": { "path": "pageable.pageNumber", "value": 0 },
        "maxMs": 300                       //   бюджет времени (не ломает вердикт, но виден в отчёте)
      }
    },
    { "name": "detail", "method": "GET", "path": "/api/v1/pools/{{poolId}}",    "weight": 3 }
  ],
  "load": {
    "vus": 10,                             // виртуальные пользователи (max 200)
    "durationSec": 60,                     // max 900
    "rampUpSec": 5,                        // плавный старт VU
    "thinkTimeMs": [50, 200],              // пауза между запросами VU
    "maxRps": 50,                          // глобальный потолок (опционально)
    "workers": 1                           // потоков-генераторов на разные ядра (см. «Параллелизм»)
  },
  "thresholds": { "p95Ms": 500, "errorRatePct": 1 },  // критерии PASS/FAIL
  "allowWrites": false                     // true + флаг --allow-writes для POST/PUT/DELETE
}
```

Встроенные placeholders: `{{uuid}}`, `{{ts}}`, `{{randInt:1-100}}`.
Extract-пути: `content[*].id` (Spring Page), `[*].id` (массив), `data.items[0].id`.
Пути файлов в `vars.file` — относительно папки сценария. Провал контентной проверки
(`notEmpty`/`bodyContains`/`jsonPath`/`jsonPathEquals`) считается ошибкой вида `check`
(сервер ответил 2xx, но содержимое неверное) и попадает в errorRatePct.

Семантика, о которой стоит знать:
- внутри одного HTTP-вызова каждый `{{var}}` выбирается ОДИН раз — повторные вхождения
  (например, в path и в body) получают то же значение, и именно оно попадает в разбивку
  «ВЛИЯНИЕ ПАРАМЕТРОВ»;
- `jsonPathEquals` с `[*]`-путём требует совпадения ВСЕХ значений (и хотя бы одного);
  `value` — только скаляр;
- имена запросов должны быть уникальны (метрики агрегируются по имени) — validate следит;
- CSV разбирается по RFC 4180 (кавычки, запятые и переводы строк в полях), UTF-8 BOM
  в файлах срезается автоматически.

## Что умеет отчёт

- перцентили p50/p90/p95/p99/max по каждому запросу и суммарно (латентность считается
  до последнего байта тела ответа, только по успешным);
- распределение статусов, классификация ошибок (timeout / conn / 4xx / 5xx / check)
  с примерами тел;
- секция «ПРОВЕРКИ ОТВЕТОВ»: сколько раз провалилась каждая валидация, сколько ответов
  вышло за бюджет maxMs, пример провала;
- секция «ВЛИЯНИЕ ПАРАМЕТРОВ (списки)»: если запрос параметризован списком ({{var}}),
  метрики считаются по каждому значению — диапазон p95 (min/медиана/max), топ медленных
  значений, выбросы по скорости (p95 > 2× медианы) и по ошибкам (err% ≥ 10). Отдельно
  распознаётся случай «падают все значения» → причина в запросе, а не в данных;
- детект деградации (p95 второй половины прогона >> первой);
- проверка порогов → `VERDICT: PASS|FAIL` и коды выхода: 0 PASS, 1 ошибка конфига,
  2 FAIL, 3 цель недоступна;
- рекомендации («это rate limit — задайте maxRps», «система держит — повышайте --vus»,
  «значение X аномально медленное»);
- в консоли — ASCII-гистограмма распределения латентности и цветной вердикт
  (PASS зелёный / FAIL красный; цвет гасится при `NO_COLOR` или пайпе в файл);
- выгрузки одним прогоном: `--out file.json` (машиночитаемо: checksSummary, paramImpact,
  hints, hist…), `--html report.html` (самодостаточный отчёт со SVG-графиком, без внешних
  ресурсов), `--junit junit.xml` (для CI), `--md report.md` (для PR/тикета).

## Сценарии-цепочки (flows / user journeys)

`requests` — независимая **смесь** (VU дёргает запросы случайно по весам). Для транзакционных
путей, где шаги идут по порядку и связаны данными, есть `flows` — упорядоченные **цепочки**:

```jsonc
"flows": [{
  "name": "add-liquidity",
  "weight": 3,                              // как часто VU выбирает эту цепочку
  "steps": [
    { "name": "list pools", "method": "GET", "path": "/api/v1/pools?size=20",
      "checks": { "jsonPath": "content[*].id" },
      "capture": { "poolId": "content[0].id", "bin": "content[0].activeBinId" } },
    { "name": "open pool", "method": "GET", "path": "/api/v1/pools/{{poolId}}" },
    { "name": "add liquidity", "method": "POST", "path": "/api/v1/positions",
      "body": { "poolId": "{{poolId}}", "binId": "{{bin}}", "amount": "100" } }
  ]
}]
```

- Каждый VU проходит `steps` **по порядку** как одну сессию, затем повторяет.
- `capture: { "имя": "json.путь" }` извлекает значение из ответа шага в `{{имя}}` для
  **следующих** шагов (session-scope). Захваченное недоступно текущему и прошлым шагам —
  валидатор это проверяет.
- Если шаг падает (статус/проверка/захват) — остальные шаги сессии пропускаются.
- Отчёт добавляет секцию **«СЦЕНАРИИ-ЦЕПОЧКИ»**: % сессий, дошедших до конца, p50/p95
  суммарного времени ответов journey (только латентность цели — без rate-gate пауз и
  think-time) и **шаг, на котором чаще всего рвётся** (узкое место сценария).
- `requests` и `flows` можно задавать вместе (фоновая смесь + журналы). Обратная
  совместимость полная: сценарий только с `requests` работает как раньше.
- Один прогон `--smoke` проходит каждую цепочку целиком один раз — проверяет всю проводку
  (включая capture) до нагрузки.

## Многоступенчатый профиль (`load.stages`) — ramp / spike / soak

Постоянная нагрузка (`vus` + `durationSec`) не находит точку излома. Для этого — ступени:

```jsonc
"load": {
  "stages": [
    { "vus": 50,  "durationSec": 30 },   // разгон 0 → 50 за 30с
    { "vus": 200, "durationSec": 60 },   // разгон 50 → 200 за 60с (ищем предел)
    { "vus": 200, "durationSec": 120 },  // плато на 200 (soak — утечки/деградация)
    { "vus": 0,   "durationSec": 30 }    // плавный спад
  ],
  "thinkTimeMs": [50, 200]
}
```

- Число активных VU интерполируется линейно между уровнями (старт с 0). Пик выводит `vus`,
  сумма длительностей — общую продолжительность (`stages` заменяет `vus`/`durationSec`).
- Прогресс каждые 5с показывает текущий целевой уровень `цель=NVU`.
- Работает в многопотоке (все потоки делят один график по времени старта).
- При stages детект «деградации во времени» отключается (рост латентности к концу —
  следствие роста нагрузки, а не утечки); смотрите на форму RPS/латентности по ходу.

## Жизненный цикл: `setup` и `teardown` (безопасные write-тесты)

Чтобы нагружать транзакционные API (создать → нагрузить → убрать за собой), сценарий может
иметь фазы, выполняемые ОДИН раз до и после нагрузки:

```jsonc
"setup": [                                    // один раз ДО нагрузки, по порядку
  { "name": "create position", "method": "POST", "path": "/api/v1/pools/add-liquidity",
    "body": { "poolId": "{{poolId}}", "amountX": 100000, ... },
    "capture": { "positionId": "positionId" } }   // захват доступен нагрузке и teardown
],
"requests": [ /* нагрузка использует {{positionId}} */ ],
"teardown": [                                 // один раз ПОСЛЕ, best-effort (даже при обрыве)
  { "name": "remove position", "method": "POST", "path": "/api/v1/pools/remove-liquidity",
    "body": { "positionId": "{{positionId}}", "percentageBps": 10000 } }
],
"allowWrites": true
```

- **setup** выполняется последовательно; захваты (`capture`) идут в общий scope — их видят и
  нагрузка, и teardown. Если setup упал — нагрузка НЕ запускается, тут же прогоняется teardown
  (откат созданного), exit 3.
- **teardown** выполняется всегда после нагрузки, best-effort: одна упавшая очистка не
  останавливает остальные. Если что-то не убралось — в отчёте явное предупреждение
  «созданные сущности могли остаться».
- Отчёт: секция «ЖИЗНЕННЫЙ ЦИКЛ» с исходом каждого шага setup/teardown.
- write-методы в setup/teardown учитываются защитой allowWrites (как и в нагрузке).

## Метрики цели: генератор или сервис — кто узкое место (`monitor`)

Секция «РЕСУРСЫ ГЕНЕРАТОРА» отвечает «упёрлись ли МЫ». Блок `monitor` добавляет вторую половину —
метрики **самой цели** во время прогона, чтобы отличить «медленно из-за генератора» от
«медленно из-за сервиса»:

```jsonc
"monitor": {
  "intervalSec": 5,
  "docker": { "containers": ["dlmm-pool-engine", "dlmm-gateway"] },   // через `docker stats`
  "prometheus": {                                                     // или/и через Prometheus
    "url": "http://localhost:9090",
    "queries": {
      "pool-engine CPU %": "rate(process_cpu_seconds_total{instance=\"dlmm-pool-engine:8080\"}[1m])*100",
      "heap used МБ": "sum(jvm_memory_used_bytes{area=\"heap\"})/1048576"
    }
  },
  "kafka": {                                                          // и/или consumer-lag Kafka
    "command": ["kafka-consumer-groups", "--bootstrap-server", "localhost:9092"],
    "groups": ["dlmm-price-oracle-ohlcv", "dlmm-transaction-service-swaps"]
  },
  "thresholds": { "dlmm-pool-engine CPU %": { "max": 85 }, "dlmm-price-oracle-ohlcv lag": { "max": 1000 } }
}
```

- **docker** — `docker stats` по контейнерам (CPU %, память), без PromQL, для любой docker-цели.
- **prometheus** — произвольные PromQL-запросы (агрегируйте до одного значения через `sum()/avg()`).
- **kafka** — consumer-lag групп: для каждой `kafka-consumer-groups --describe --group G`, сумма LAG
  по партициям → метрика `«<группа> lag»`. `command` — база вызова (клиент в PATH или через
  `docker exec`); инструмент сам добавит `--describe --group`. **Растущий по ходу прогона лаг =
  backpressure** (консьюмер не успевает) → в отчёте «⚠ РАСТЁТ» + подсказка, что узкое место на
  стороне обработки, а не Kafka. (Метрики кэша Ignite — через `prometheus`, Ignite экспортит JMX.)
- Отчёт: секция «МЕТРИКИ ЦЕЛИ» с min/avg/max/пиком/последним по каждой метрике.
- **Корреляция**: если CPU цели дошёл до насыщения, а генератор — нет, инструмент прямо пишет
  «⚠ ЦЕЛЬ упёрлась — предел в самом сервисе». Если ни то, ни другое, а латентность высокая —
  подсказывает искать вне CPU (БД, блокировки, GC, внешний сервис).
- `monitor.thresholds` (опц.) — порог по метрике цели (CPU/память/lag) входит в вердикт; имена:
  docker `«<конт> CPU %»`/`«<конт> MEM МБ»`, prometheus — имя запроса, kafka `«<группа> lag»`.

## Распределённый прогон с N машин (`merge`)

Когда одна машина упирается в ресурсы (секция «РЕСУРСЫ ГЕНЕРАТОРА» пишет «нужно более мощное
железо / несколько машин»), запусти один сценарий на нескольких машинах и слей результаты:

```bash
# на каждой машине (одновременно, против одной цели):
node load-agent/bin/loadgen.mjs run scenario.json --out result-$(hostname).json
# затем собрать результаты в одно место и агрегировать:
node load-agent/bin/loadgen.mjs merge result-host1.json result-host2.json result-host3.json
```

- Суммирует пропускную способность (**RPS всех машин**) и общее число запросов.
- Перцентили считаются из **гистограмм латентности** (в каждом результате есть поле `hist`):
  гистограммы складываются точно, так что p50/p95/p99 агрегата корректны (с точностью до ширины
  бакета), а не «среднее средних». Вердикт PASS/FAIL пересчитывается по порогам (exit 0/2).
- Требует результаты v1.11+ (в них есть `hist`); старые/чужой версии сетки/частичные отвергаются
  с внятной ошибкой. Запускайте прогоны **одновременно и одинаковой длительности** — суммарный
  RPS осмыслен только для пересекающихся окон (длительность агрегата = максимум из прогонов).

## Параллелизм и ресурсы генератора

`load.workers` (или `--workers N`) распределяет VU по нескольким потокам `worker_threads`,
каждый на своём ядре — это снимает потолок «одно ядро Node» при высоких RPS. Статистика
всех потоков сливается в один отчёт. Разумный предел — число ядер машины.

Отчёт **всегда** содержит секцию «РЕСУРСЫ ГЕНЕРАТОРА»: CPU процесса (в % от одного ядра и от
всех), event-loop lag, память процесса и системы. Если генератор упёрся (CPU ≥ 85% всех ядер,
event-loop lag > 100мс или мало свободной ОЗУ) — инструмент прямо пишет **«⚠ УПЁРЛИСЬ»** и
предупреждает, что латентность и достигнутый RPS ограничены самой машиной-генератором, а не
целью. В подсказках: либо «добавьте workers» (если ядра ещё свободны), либо «нужно более мощное
железо / несколько машин» (если все ядра уже заняты). Это защищает от вывода «сервис медленный»,
когда на самом деле медленный генератор.

## Профиль нагрузки из статистики (`profile`)

Если есть историческая статистика обращений — превратите её в реалистичный сценарий:

```bash
node load-agent/bin/loadgen.mjs profile access.log --base-url http://host --top 20 --out scenario.json
```

Вход: access-лог (nginx/gateway), CSV (`path,method,count`) или JSON (`[{method,path,count}]` /
`{"GET /x": 1994}`) — формат определяется автоматически. Команда:
- нормализует пути: ID-сегменты (числа, UUID, длинные hex) → `{{переменные}}`, а реальные
  значения из статистики складывает в `vars` (нагрузка идёт по настоящим ID);
- считает `weight` каждого запроса как долю хитов — смесь повторяет продовую;
- из таймстемпов access-лога оценивает средний RPS → `load.maxRps`;
- по умолчанию отбрасывает POST/PUT/DELETE (нужен `--include-writes`).

Результат — **черновик**: впишите `baseUrl`/`auth`, при желании `workers`, затем обычный
`validate → run --smoke → run`. Подробный разбор для агента — `agent/PROFILE_FROM_STATS.md`.

## Интеграция в код (Node.js / Python / Java / Scala)

Готовые обёртки в [clients/](clients/README.md): движок один, клиенты запускают его
процессом и отдают результат в родном виде — удобно для нагрузочных тестов в CI
(pytest / JUnit / scalatest / node:test). Единственное требование на агенте CI — Node >= 18.

## Тела не-JSON: form-urlencoded и multipart (`bodyType`)

По умолчанию тело шлётся как JSON. Для API, которые его не принимают — `bodyType`:

```jsonc
// OAuth2 token endpoint (application/x-www-form-urlencoded)
{ "method": "POST", "path": "/oauth/token", "bodyType": "form",
  "body": { "grant_type": "password", "username": "{{user}}", "password": "${PW}" } }

// загрузка файла (multipart/form-data)
{ "method": "POST", "path": "/upload", "bodyType": "multipart",
  "body": { "meta": "v1", "file": { "file": "data/payload.bin", "filename": "p.bin", "type": "application/octet-stream" } } }
```

- `form` — тело `key=value&…` с URL-кодированием; значения — скаляры, плейсхолдеры работают.
- `multipart` — строковые поля + файловые `{ "file": "путь", filename?, type? }`; путь от папки
  сценария, содержимое файла читается один раз и кэшируется; Content-Type с boundary ставится сам.
- Content-Type проставляется автоматически (json/form/multipart), ручной не нужен.

## Секреты из окружения (`${VAR}`) — сценарий без паролей в файле

Любую строку сценария можно взять из переменной окружения — пароли/токены не хранятся в JSON
и не попадают в git, а инжектятся при запуске (в т.ч. в CI):

```jsonc
"baseUrl": "${TARGET_URL:-http://localhost:8080}",   // с дефолтом
"auth": { "type": "bearer", "token": "${API_TOKEN}" },
"login": { "body": { "password": "${LOADGEN_PW}" } }
```

- `${VAR}` — обязательная; если не задана и нет дефолта, прогон падает с внятным списком того,
  что нужно экспортировать. `${VAR:-дефолт}` — со значением по умолчанию.
- Пустая переменная считается незаданной (идёт дефолт). `$${VAR}` — литерал `${VAR}`.
- Подстановка идёт по всему сценарию (baseUrl, auth, headers, тела, пути, monitor…), один раз
  при загрузке. Запуск: `LOADGEN_PW=secret node bin/loadgen.mjs run scenario.json`.
- `{{var}}`-плейсхолдеры работают и в **заголовках** — можно прокинуть захваченный в цепочке
  токен: `"headers": { "Authorization": "Bearer {{token}}" }` или `"X-Idempotency-Key": "{{uuid}}"`.
- Флаг `--base-url` подменяет `baseUrl` **до** подстановки env — так `"baseUrl": "${TARGET_URL}"`
  без дефолта можно закрыть из CLI (`--base-url http://host`), не экспортируя переменную.

## Разбивка латентности: TTFB vs скачивание тела

Отчёт разбивает латентность на две части — чтобы отличить «сервер долго думает» от
«тяжёлый ответ качается»:

- **TTFB** (time-to-first-byte) — время до прихода заголовков ответа: сеть + обработка на
  сервере + первый байт. Растёт TTFB → узкое место в обработке/сети.
- **Скачивание** — время передачи тела (total − TTFB). Растёт скачивание → тяжёлый ответ.

В блоке ИТОГ: `из p95: TTFB ~Xms, скачивание ~Yms`. В JSON — `latencyMs.ttfbP95` и per-request
`ttfbP95`/`downloadP95`. Если скачивание съедает заметную долю p95, инструмент подсказывает
конкретный эндпоинт и советует пагинацию/выборку полей/сжатие.

## Регрессии: сравнение прогонов (`compare`) — CI-гейт

Один прогон — снимок. Чтобы ловить «этот PR ухудшил p95», сравнивайте текущий результат
с эталонным:

```bash
node load-agent/bin/loadgen.mjs compare baseline.json current.json
# или сразу после прогона:
node load-agent/bin/loadgen.mjs run scenario.json --out current.json --baseline baseline.json
```

- Сравнивает p95/p99/долю ошибок/RPS суммарно **и по каждому запросу**, плюс завершаемость
  цепочек. Локальный регресс одного эндпоинта не спрячется за хорошим средним.
- Пороги: рост p95 > `--max-p95-regression-pct` (по умолч. 20%, с полом шума 5ms) или рост
  ошибок > `--max-error-increase-pp` (по умолч. 1 пункт) → **VERDICT: REGRESSED, exit 2**.
- Заметное улучшение → `IMPROVED`, иначе `STABLE` (оба exit 0).

**Паттерн для CI**: храните `baseline.json` (эталон с main), в пайплайне запускайте
`run ... --baseline baseline.json` — ненулевой код выхода валит сборку при регрессе.
Периодически обновляйте базу принятым прогоном.

### Отчёты для CI: JUnit XML и Markdown

```bash
node load-agent/bin/loadgen.mjs run scenario.json --junit report.xml --md report.md
```
- `--junit` — стандартный JUnit XML: каждый порог/запрос/цепочка становится test-case'ом,
  который Jenkins/GitLab/GitHub Actions рендерят как обычные результаты тестов (провал порога =
  failing test). `--md` — Markdown-сводка для комментария в PR (вердикт, таблица, цепочки, ресурсы).

### Пороги на конкретный запрос (SLO по эндпоинту)

Глобальные `thresholds.p95Ms`/`errorRatePct` дополняются порогами на отдельный запрос:
```jsonc
"thresholds": {
  "p95Ms": 1000, "errorRatePct": 1,
  "perRequest": {
    "pools list":  { "p95Ms": 100 },
    "pool detail": { "p95Ms": 300, "errorRatePct": 0 }
  }
}
```
Нарушение per-request порога тоже валит вердикт (и виден отдельным test-case'ом в JUnit).

Кроме `p95Ms`/`errorRatePct` доступны SLO по хвосту и пропускной:
```jsonc
"thresholds": {
  "p95Ms": 500, "p99Ms": 1000,   // хвост латентности (p99 — стандарт для SLO)
  "errorRatePct": 1,
  "rpsMin": 500,                  // минимальная пропускная (fail, если ниже) — в merge это СУММАРНЫЙ RPS
  "perRequest": { "checkout": { "p99Ms": 800 } }
}
```

### Разогрев (`load.warmupSec`)

`"load": { ..., "warmupSec": 10 }` — первые 10 секунд (JIT, прогрев пула соединений, кэшей)
**не учитываются** в метриках. Перцентили считаются по установившемуся режиму; RPS — по окну
после разогрева. С `stages` не применяется (нагрузка не постоянна).

## Нагрузка на СУБД напрямую (`kind:"sql"`) — Postgres/Ignite

Иногда узкое место не в REST-слое, а в самой базе. `kind:"sql"` гоняет нагрузку **прямо на СУБД**,
переиспользуя весь движок (VUs/workers/stages/warmup/перцентили/пороги/param-impact/отчёт) — меняется
только транспорт. **Zero-dep сохраняется**: инструмент шелится в родной CLI СУБД (как для `docker stats`),
поэтому нужен клиент в PATH (`psql`, `sqlline`) — или запуск через `docker exec`.

```jsonc
{
  "kind": "sql",
  "sql": {
    "driver": "psql",                                  // psql | sqlline | custom
    "command": ["psql", "postgresql://user:pass@localhost:5432/db"]
    // или через docker: ["docker","exec","-i","pg","psql","-U","app","-d","app"]
  },
  "vars": { "lim": [10, 50, 100] },                    // {{var}} работают и в SQL
  "requests": [
    { "name": "count", "sql": "select count(*) from orders", "weight": 3,
      "checks": { "minRows": 1, "maxMs": 20 } },        // проверки: minRows / notEmpty / maxMs
    { "name": "scan",  "sql": "select id from orders limit {{lim}}", "weight": 1 }
  ],
  "load": { "vus": 8, "durationSec": 20, "warmupSec": 3 },
  "thresholds": { "p95Ms": 50, "p99Ms": 150, "errorRatePct": 0, "perRequest": { "count": { "p95Ms": 20 } } }
}
```

Быстрый старт: `node bin/loadgen.mjs init --preset sql-postgres --out pg.json`.

- **Персистентное соединение на VU:** каждый VU держит ОДИН клиентский процесс (= одно соединение),
  как реальный пул — а не «форк psql на каждый запрос». Латентность берётся **серверная** (таймер
  драйвера, напр. psql `\timing`), без шума pipe; если драйвер её не печатает — падаем на wall-clock.
- **Шаг** задаётся полем `sql` (вместо `method`/`path`). `checks`: `minRows` (>= N строк), `notEmpty`,
  `maxMs` (бюджет). Отчёт — те же перцентили/гистограмма/«ВЛИЯНИЕ ПАРАМЕТРОВ» по `{{var}}`.
- **Запись** (INSERT/UPDATE/DELETE/DDL) распознаётся по ключевым словам и требует `allowWrites:true` +
  `--allow-writes` (та же двойная защита). `setup`/`teardown` работают — удобно создать таблицу до
  нагрузки и удалить после.
- **Ошибки и надёжность:** для `psql` статус запроса читается **в канале stdout** (`:ERROR`/`:SQLSTATE`),
  а не по гонке stderr — детект ошибок точный. Для `sqlline`/`custom` без `statusRegex` ошибка берётся
  из stderr (best-effort, validate предупредит). Плюс на `psql` ставится серверный `statement_timeout` =
  `timeoutMs`: даже если клиент убьют по таймауту, backend СУБД сам снимет затянувшийся запрос — генератор
  не «завешивает» цель на `max_connections`.
- **Ignite:** `driver:"sqlline"` (thin JDBC) — рабочая заготовка; тонкости под версию переопределяются
  в `sql`-блоке (`init`/`mark`/`timeRegex`/`rowsRegex`/`errorRegex`/`statusRegex`). Либо Ignite REST — обычным `kind:"http"`.
- **`driver:"custom"`** — любой клиент с интерактивным вводом (mysql, clickhouse-client, redis-cli…):
  задайте `command` + `mark` (маркер конца результата) + при желании `timeRegex`/`rowsRegex`/`errorRegex`/`statusRegex`.
- **Write-guard** для SQL — эвристика по ключевым словам (`insert/update/…/vacuum/refresh/select…into`),
  заведомо неполная: read-only у SQL не гарантируется как у HTTP-метода, поэтому лучше пере-флагнуть.
  `minRows`/`notEmpty` считают ВОЗВРАЩЁННЫЕ строки (SELECT/RETURNING) — на чистом INSERT их не будет.
- ⚠ `{{var}}` подставляются в текст запроса как есть (не bind-параметры) — значения из доверенных
  списков/файлов, не из недоверенного ввода (иначе SQL-инъекция в самом сценарии).

## Сквозной лаг конвейера (`kind:"pipeline"`) — Kafka→PG/Ignite

REST-тест не видит асинхронный конвейер. `kind:"pipeline"` меряет **сквозную задержку**: publish
сообщение с корреляционным ключом `{{corr}}` → поллит витрину, пока запись не появится → лаг =
«отправлено → видно». Это SLA конвейеров вроде Kafka→Postgres: «за сколько событие долетает до
витрины и **сколько теряется** под нагрузкой». Быстрый старт: `init --preset pipeline-kafka-pg`.

```jsonc
{
  "kind": "pipeline",
  "allowWrites": true,                                  // publish = запись, нужен ещё --allow-writes
  "produce": {
    "command": ["kafka-console-producer","--bootstrap-server","localhost:9092","--topic","events","--property","parse.key=true","--property","key.separator=:"],
    "message": "{{corr}}:{\"id\":\"{{corr}}\",\"v\":{{randInt:1-999}}}"   // {{corr}} — уникальный ключ на событие
  },
  "verify": {
    "sql": { "driver":"psql", "command":["psql","postgresql://user:pass@host/db"] },
    "query": "select 1 from events_sink where corr='{{corr}}' limit 1"   // строка появилась = долетело
  },
  "pipeline": { "pollIntervalMs": 50, "timeoutMs": 10000 },
  "load": { "vus": 4, "durationSec": 30, "warmupSec": 3 },
  "thresholds": { "p95Ms": 2000, "p99Ms": 5000, "errorRatePct": 1 }
}
```

- Каждый VU: сгенерировать `{{corr}}` (uuid) → publish → поллить `verify.query` каждые `pollIntervalMs`,
  пока не вернёт строку (или `pipeline.timeoutMs`). Лаг = время до материализации.
- Отчёт — те же перцентили/гистограмма, но подписаны как **«лаг распространения»**; ошибки = **«застряло»**
  (не долетело за timeout). Событие, посланное под конец теста и не успевшее материализоваться, НЕ
  считается застрявшим (как прерванная flow-сессия) — иначе хвост ложно раздул бы потери.
- `produce` — persistent-клиент (kafka-console-producer/`kcat -P`), сообщения построчно в stdin;
  fire-and-forget, момент старта ≈ запись в stdin (буфер продьюсера добавляет небольшую константу).
- `verify` — persistent SQL-сессия (тот же движок, что `kind:"sql"`; надёжнее `driver:"psql"`).
  Для Ignite витрины — sqlline/REST; сообщение можно проверять и в самой Kafka (consumer как verify — на будущее).
- Всегда **однопоточно** (поллинг I/O-bound, `load.workers` игнорируется). `setup`/`teardown` пока не
  выполняются — sink-таблицу подготовьте отдельным `kind:"sql"`-сценарием или вручную.

## Тесты самого движка

Регрессионные self-тесты ядра (парсинг, проверки, захват, валидация flows, слияние
статистики) — в [test/](test/): `cd load-agent && node --test`. Зависимостей нет.

## Предохранители (важно для командного использования)

1. **Запись** — двойное подтверждение: `allowWrites: true` в сценарии **и** флаг `--allow-writes`.
2. **Внешние хосты** — нагрузка только на localhost/приватные сети; для своего внешнего
   стенда нужен явный флаг `--confirm-external`. Нагружать чужие системы недопустимо.
3. **Жёсткие лимиты**: vus ≤ 200, duration ≤ 900с — в коде, обойти флагом нельзя.
4. Смоук перед нагрузкой — агент обязан прогнать `--smoke` до полного запуска.

## Почему это работает на слабых моделях

| Обычный подход | Здесь |
|---|---|
| модель пишет скрипт нагрузки | скрипт уже написан и оттестирован |
| модель считает перцентили/RPS | считает CLI, модель копирует блок «ИТОГ» |
| модель интерпретирует сырые ошибки | CLI печатает готовые «ПОДСКАЗКИ» |
| модель решает, валиден ли конфиг | `validate` печатает точные «✗ ...» с инструкцией |
| свободный формат отчёта | промпт фиксирует: скопируй ИТОГ → таблицу → подсказки |

Известная особенность демо-стенда DLMM: на gateway включён rate limit (~10 rps на ключ в
текущем образе), поэтому в `dlmm-read-heavy.json` стоит `maxRps: 8`. Уберите его, если хотите
увидеть, как инструмент репортит 429 и находит предел пропускной способности.
