# UI live-observations — 2026-05-22

> Прошёл по реальным экранам платформы в браузере (Chrome + MCP).
> Залогинился как seed-user `ivanov@example.com / Demo1234` в user-ui
> (localhost:3001) и `admin@sber-dlmm.ru / Demo1234` в admin-ui
> (localhost:3000). Все 11 ключевых экранов captured.

## Важный контекст: разрыв между repo HEAD и running container

**Что я увидел вживую — это BEFORE-state**, до моих 6 коммитов
сегодняшней сессии (UI-CRITIQUE waves 1-4). Docker-compose
контейнер был собран до Sprint 10-11 пушей; новый код TS-чисто
компилируется и тесты зелёные (187 vitest), но **не задеплоен**:

| Что shipped в коде | Видно в running UI |
|---|---|
| ✅ Sprint 10 N-03 Health Score column | ❌ нет колонки на /positions |
| ✅ Sprint 10 N-04 Auto-claim card | ❌ нет в Profile |
| ✅ Sprint 11 G-20 2FA card | ❌ нет |
| ✅ Sprint 11 G-21 Team page | ❌ нет в sidebar |
| ✅ Sprint 12 G-14 RiskDisclosure | ❌ нет на /hedge, /liquidity |
| ✅ Wave 1A typography tokens | ❌ inline px ещё на странице |
| ✅ Wave 1B brand-green aliases | ❌ старые green вызовы |
| ✅ Wave 2A hero rebalance | ❌ старый padding 36px |
| ✅ Wave 2C Profile Tabs | ❌ один column как раньше |
| ✅ Dark mode theme | ❌ `data-theme="dark"` не имеет CSS overrides в running build |

**To deploy:** `docker-compose build dlmm-user-ui dlmm-admin-ui && docker-compose up -d`.

---

## 1. User-ui — Dashboard `/#/` (BEFORE state)

**Что работает:**
- Brand identity моментально читается — green hero + checkmark
- 4 sub-метрики в hero (Активные позиции / Незабр. комиссии / Доход)
  правильно структурированы
- "Быстрые действия" 3-row card в центре — clear CTA hierarchy
  (Свопнуть → SRUB primary green; Открыть FX-хедж secondary;
  Добавить ликвидность secondary)
- "Последние операции" right column — 5 obmen с временем + статусом
- "Мои токены" таблица ниже

**Что не работает (real visual issues):**

| # | Issue | Severity |
|---|---|---|
| L-01 | **"8.06 квд ₽"** — квадрильоны в hero portfolio. Это seed-data artefact, но user видит и считает что платформа сломана. Должен быть либо реалистичный formatter cap (e.g. >999B → "много") либо сидинг fix. | **High** |
| L-02 | Hero takes 60% of above-the-fold — **подтверждено вживую**. Repeat-visit user видит загорающийся анимированный градиент и потом ищет actions ниже. | **High** (этот fix в моём commit `872b9a2` ждёт redeploy) |
| L-03 | Notification badge **"37"** на bell без contextual hint — растёт нескольких сессий, ни одной не прочитал. UX antipattern для "all unread count". | Med |
| L-04 | СберСпасибо card левый column (33%) — содержит form "Сколько баллов?" + кнопку "В рубли" disabled. Form в Dashboard это weird (Dashboard = overview, не transaction). | Med |
| L-05 | "Мои токены" таблица: header "ТОКЕН" занимает ~30% width при содержимом ~50px. Empty horizontal space. | Low |

---

## 2. User-ui — Positions `/#/positions`

**Что работает:**
- 3 KPI tiles сверху — clear summary (АКТИВНЫХ ПОЗИЦИЙ 11, etc)
- Каждая row показывает: token-pair chip + strategy chip + диапазон
  цен + комиссии + кнопки

**Что не работает:**

| # | Issue | Severity |
|---|---|---|
| L-06 | Column header **"НЕЗАБРАННЫЕ КОМИССИИ"** один — но values в **двух колонках** (+120.00 млн SBTC | +60.00 млн SRUB). Header должен быть split на X/Y или включать обе единицы. | **High** |
| L-07 | "Диапазон цен" для SBTC — "4 757 328,44 — 5 255 050,25 SRUB" — числа корректные, но длина (5.2M SRUB per SBTC) делает header truncation problem. Tabular numerics OK. | Low |
| L-08 | 3 KPI tiles — каждая с padding 32px+. Тратится много vertical space до того как user видит таблицу. На laptop 1366×768 user видит только KPI + 4 строки таблицы. | Med |
| L-09 | **Нет Health Score column** — это мой Sprint 10 N-03 ship, ждёт redeploy | (would-fix) |
| L-10 | **Нет "Закрыть всё" / "Алерты" buttons** — ждёт redeploy | (would-fix) |

---

## 3. User-ui — Swap `/#/swap`

**Что работает:**
- "Популярные пары" strip — отличная discovery surface; 5 pair chips горизонтально
- Slippage badge "Скольжение 0.5%" top-right — discoverable но не вмешивается
- "Готовы к обмену?" empty-state hint в panel

**Что не работает:**

| # | Issue | Severity |
|---|---|---|
| L-11 | **Width imbalance: swap form занимает 35%, "Топ пулов по ликвидности" 65%**. Главная функция (swap) меньше второстепенной (info). Должно быть 50/50 или swap главнее. | **High** |
| L-12 | "Топ пулов по ликвидности" rows — token pair symbols **слева**, цифры **справа**, между ними ~700px пустого пространства. Эти данные неактивные на pages (не кликабельные на сам swap?). | Med |
| L-13 | Token icons на разных цветных бэйджах (SBER=зелёный, SUSDT=синий, LKOH=тёмно-синий, YNDX=жёлтый, SEUR=оранжевый, SBER=зелёный). Brand colours каждого токена — OK, но недостаточная visual cohesion как icon family. | Low |

---

## 4. User-ui — Pools `/#/pools`

**Что работает:**
- 3×4 = 12 pool cards на screen — high info density без overload
- Каждая card: token-pair icons + symbol + Активен tag + шаг/комиссия + 3 KPI (TVL, ОБЪЁМ 24Ч, APY) + Primary "Добавить ликвидность" + "Подробнее →" link
- Pagination "1 | 2 | >" внизу

**Что не работает:**

| # | Issue | Severity |
|---|---|---|
| L-14 | **Все APY = 0.00%** (12 из 12 cards). Зелёная иконка молнии перед нулём — выглядит как фейк/баг. Должен быть либо "—" if недостаточно данных, либо real APY. | **High** (data, не design) |
| L-15 | **Все "ОБЪЁМ 24Ч = 0 ₽"** — same problem. Если данных нет, лучше скрыть row entirely или сказать "новый пул". | Med |
| L-16 | "Подробнее →" — это link под Primary button. Может быть избыточной (Card сам кликабельный?). User видит две CTAs для одного пула. | Low |
| L-17 | Search input "Найти пару (SBER, GAZP, SRUB…)" — top-right, не имеет clear discovery. На viewport 1568 видно, но при 1024 — потеряется. | Low |

---

## 5. User-ui — Profile `/#/profile`

**Что работает:**
- Identity Card в начале: large avatar + name + email + KYC badge — clear identity moment
- "Самозапрет 115-ФЗ" panel — большой блок с explanation + info Alert + danger CTA. Compliance-correct presentation
- Right rail: "Сводка по аккаунту" tile + "Последняя активность" feed — useful context
- "Стоимость портфеля 8.06 квд ₽" в sidebar повторяется (тоже квадрильоны)

**Что не работает:**

| # | Issue | Severity |
|---|---|---|
| L-18 | **Danger button "Установить самозапрет"** красный фон + lock icon — выглядит как destructive action. Но самозапрет — это **защитное** действие (user добровольно блокирует себя для собственной защиты). Должно быть type="primary" warning-shade, не danger. | Med |
| L-19 | "Самозапрет" описание содержит "**7-дневного периода охлаждения**" — нужный текст, но без визуального якоря (icon, banner). User просматривает быстро и не замечает. | Low |
| L-20 | Right rail "Сводка по аккаунту" — структура "Стоимость портфеля 8.06 квд ₽" + grid 2×1 (Активов в кошельке 22 / Активных позиций 11) + "Заработано на ликвидности 0 ₽". Дробить вертикальную информацию правильно, но "0 ₽" в финальной строке — depressing finale. | Low |

---

## 6. User-ui — Hedge `/#/hedge`

**Что работает:**
- Strong contextual header: SafetyCertificateOutlined + "Хеджирование валютного риска" + объяснение в одном блоке
- "Подверженность валютному риску 7.31 трлн SRUB" + "В заморозке 2.00 млрд SRUB" — clear exposure stat
- 3 pair cards (SRUB→SUSDT/SCNY/SEUR) с текущими котировками + комиссией
- Empty state "Калькулятор хеджа" с hint "Выберите пару слева, чтобы рассчитать хедж"
- Empty hedges section с placeholder text

**Что не работает:**

| # | Issue | Severity |
|---|---|---|
| L-21 | **Pair cards stacked vertically только в left column**, calculator на right занимает 200px. Empty space справа от cards = 600px. Layout 50/50 был бы лучше; current 50/30 with 20px unused. | Med |
| L-22 | **No RiskDisclosure banner** — мой Wave A G-14 ship ждёт redeploy. Hedge — самая financially-risky операция; risk disclosure нужен критически. | (would-fix) |
| L-23 | "Открытые хеджи 0" tag + empty state below — empty section с целым padding'ом 80px+ выглядит как "broken layout" чем "empty section". Compact mode (40px) был бы лучше. | Low |

---

## 7. User-ui — Transactions `/#/transactions`

**Что работает:**
- Filter row (Тип | Статус | Дата от → Дата до | Обновить | Сбросить) clean
- Каждая row: дата + время на отдельных строках + token-pair chip + amount in/out + комиссия + status
- "Сумма входа" / "Сумма выхода" / "Комиссия" — 3 separate right-aligned columns

**Что не работает:**

| # | Issue | Severity |
|---|---|---|
| L-24 | Amounts типа **"2.44 трлн SRUB"** и **"186.64 млрд SCNY"** — снова seed data scale issue (триллионы рублей в одной транзакции). Это не design issue per se, но пугает user'а если он реальный. | High (data) |
| L-25 | "Комиссия" column showing "2.44 млрд SRUB" — комиссия в МИЛЛИАРДАХ рублей за один своп. Очень неправдоподобно. Snapshot of demo data quality concerns. | (data) |
| L-26 | Все статусы = "Подтверждена" зелёные tags — visual repetition, без contrast чтобы найти что-то особенное. | Low |
| L-27 | Filter "Дата от" / "Дата до" range picker — default empty. Если "тип" / "статус" filters пустые, и date пустой — user видит **все** транзакции от дня X. Лучше default: last 7 days. | Low |

---

## 8. Admin-ui — Dashboard `/dashboard`

**Что работает:**
- "Total Value Locked" hero — institutional language for admin audience (vs "Ваш портфель" в user-ui)
- 4 KPI cards row 1 + 4 cards row 2 + 2 cards row 3 = density appropriate for ops
- **"Уровень верификации KYC 75% (3 из 4 прошли)"** + green progress bar — **the BEST data viz on the platform**. Compact, immediate signal, contextually correct.
- "Последние операции" left + "Топ-5 пулов по объёму" right — admin needs both

**Что не работает:**

| # | Issue | Severity |
|---|---|---|
| L-28 | "Total Value Locked **1.82 квд ₽**" — квадрильоны again. На admin это даже хуже потому что admin видит это как total platform value. | **High** (data) |
| L-29 | **Three rows of KPI cards** 4+4+2 — последняя row выглядит асимметрично. Could be 3+3+3 or 4+4+4 (с placeholder). | Med |
| L-30 | "Последние операции" rows — "07:22:05 | Своп | 2 438 141 618 927 | Исполнен" — **без token/pair context**. Admin не знает что обменялось — рубли? SBER? Без entity hint таблица не information-dense, а просто numbers wall. | Med |
| L-31 | "Топ-5 пулов по объёму" — все 5 показывают "0 ₽" объём; "0.0% от объёма" subscript. Section потеря смысла when nothing has happened. Better — collapse or show "За 24h операций не было". | Low |

---

## 9. Admin-ui — Pools `/pools`

**Что работает:**
- 4 KPI tiles top — clean (АКТИВНЫХ ПУЛОВ 20/20, СОВОКУПНЫЙ TVL, ОБЪЁМ ЗА 24Ч, СРЕДНИЙ APY)
- Filter row (Статус | Сортировка | "20 из 22") — minimal but functional
- Table columns: ПАРА | ШАГ БИНА | БАЗОВАЯ КОМИССИЯ | ТЕКУЩАЯ ЦЕНА | РЕЗЕРВ X | РЕЗЕРВ Y | ОБЪЁМ 24Ч | РАСЧ. APY | СТАТУС
- "Создать пул" primary button top-right

**Что не работает:**

| # | Issue | Severity |
|---|---|---|
| L-32 | "Резерв X" / "Резерв Y" cells show "24.99 млн SRTSI" / "27.46 трлн SRUB" — symbol baked in cell. Mixed units (млн vs трлн) в одном column — eye не может scan vertically для compare. | Med |
| L-33 | **Все РАСЧ. APY = 0.00%** + **все ОБЪЁМ 24Ч = 0** — same data issue as user-side. На admin это критическая красная флаговая ситуация. | High (data) |
| L-34 | "Средний APY" KPI tile показывает "—" (placeholder) "недостаточно данных" — admin понимает почему. Но эта tile занимает same space as полезные tiles → wasted real estate. | Low |

---

## 10. Admin-ui — OTC desk `/otc`

**Что работает:**
- **Excellent section-grouped table** — Ожидают котировки 1 / Котировка выставлена 1 / Приняты к расчёту 3. Coloured dots match section headers. Best-in-class IA on platform.
- "Соединение с биржевым шлюзом установлено" status tag — institutional confidence signal
- "Сессия 22.05.2026 · 09:30 — 18:30 SSK" — trading-floor calendar feel
- 4 KPI tiles with clear ownership: "Ожидают котировки 1 / требует ответа" — every number has subscript

**Что не работает:**

| # | Issue | Severity |
|---|---|---|
| L-35 | Инициатор + контрагент cells показывают `a0000000…` UUIDs truncated. Admin не знает кто инициатор. Должно быть email или org name. | **High** |
| L-36 | "IN" / "OUT" columns — raw numbers like "50 000 000 000" без unit symbol. Admin может неправильно посчитать magnitude. Add "(SRUB)" / "(SUSDT)" inline или к header. | Med |
| L-37 | "Архив сегодня 1" — collapsed/expanded state UX unclear. Hint что эта section имеет further data. | Low |

---

## 11. Admin-ui — Suspicious `/transactions/suspicious`

**Что работает:**
- **Лучший empty state на всей платформе** — large green check + "Подозрительных транзакций не обнаружено / Платформа работает в штатном режиме. Автообновление каждые 30 сек."
- Header tag "чисто" (green) — instant status reading
- 4 KPI tiles all показывают 0 — consistent с no-flags status

**Что не работает:**

| # | Issue | Severity |
|---|---|---|
| L-38 | (Nothing significant. Это — образец good empty state.) | — |

---

## 12. Dark mode — попытка

Я попытался toggle dark mode через JS:
```js
localStorage.setItem('dlmm.user.theme', 'dark');
document.documentElement.setAttribute('data-theme', 'dark');
```

**Результат:** ничего не изменилось.

**Причина:** `themeStore` + `[data-theme="dark"]` CSS overrides — это
мой Sprint 9-DS-r4 P2-15 ship. В running container CSS не имеет
`[data-theme="dark"]` блока — потому dark theme не существует
до redeploy.

---

## Сводный приоритет по новым находкам

### High (deploy + fix)

| ID | Issue | Fix |
|---|---|---|
| L-01 / L-24 / L-25 / L-28 / L-33 | **Seed data в квадрильонах** — pervasive, видно везде где amount | Fix seed scripts (init-db.sql) или formatter cap (если >999B → "много триллионов") |
| L-06 | "Незабранные комиссии" с одним header, двумя columns | Split column header → "Комиссия X" / "Комиссия Y" |
| L-11 | Swap form 35% / Top Pools 65% width imbalance | Layout → 50/50 или 60/40 (swap главнее) |
| L-14 | Все APY = 0% — выглядит как баг | Если данных нет → "—" + tooltip "Недостаточно swap'ов для APY" |
| L-35 | OTC initiator/counterparty UUIDs | Display email/org instead of UUID truncate |
| **Deploy gap** | Все Sprint 10-12 фичи + UI-CRITIQUE waves 1-4 не deployed | `docker-compose build && up -d` |

### Med

| ID | Issue | Fix |
|---|---|---|
| L-03 | Notification badge "37" perpetual | Add "Mark all read" + auto-decay if не открывается |
| L-08 | Positions page — 3 KPI tiles слишком много vertical space | Compact tile mode (60% height) |
| L-15 / L-31 | Empty data sections / pools показывают 0 без контекста | Collapse + "За 24ч операций не было" message |
| L-18 | "Самозапрет" danger red для protective action | Switch to warning-orange |
| L-21 | Hedge calculator 200px right column wasted | 50/50 split |
| L-29 | Admin dashboard 4+4+2 KPI rows asymmetric | 3+3+3 grid |
| L-30 | Admin "Последние операции" без token context | Add token-pair chip per row |
| L-32 | Admin Pools "Резерв" mixed units (млн vs трлн) | Either всё в едином denominator (e.g. всё в млн или formatter ratio) |
| L-36 | OTC IN/OUT без unit | Add token symbol to header |

### Low (Sprint 13+ polish)

| ID | Issue | Fix |
|---|---|---|
| L-04 | СберСпасибо form в Dashboard | Move to dedicated /spasibo page or settings |
| L-05 / L-17 | Layout overflow on narrow viewports | Already handled in responsive sweeps; verify |
| L-13 | Token icons not coherent family | Brand-design pass |
| L-16 | Pool card double-CTA (Primary + link) | Make whole card clickable, remove "Подробнее →" link |
| L-19 / L-20 / L-23 / L-26 / L-27 | Various microcopy / spacing issues | Сweep with brand-copy editor |
| L-34 / L-37 | KPI placeholder taking equal space; archive collapse UX | Sprint 13 |

---

## Что мне СВЕРХ всего нравится (positive)

- **OTC desk admin** (`/admin/#/otc`) — best information architecture on the platform. Section-grouped table + status tags + trading-session header. Это template для других density-heavy admin страниц.
- **Suspicious transactions empty state** — best EmptyState на платформе. Имитируй этот pattern в моём `<EmptyState>` component.
- **KYC verification progress bar** на admin Dashboard — best data viz. 75% + "3 из 4 прошли" + actual progress bar. Compact и информативно.
- **Hedge page header context** — strong opening: icon + title + 2-sentence explanation в Paragraph. Template для всех product-page headers.

---

## Что узнал из live walk-through, чего НЕ видел в коде

1. **Seed data quality issue dominates UX** — квадрильоны fake numbers видны буквально на каждом экране. Это **#1 visual surprise** для new user. Изначально я искал design problems; нашёл data integrity problems которые проявляются как design problems.

2. **"Подробнее →" link на Pool card** — я в коде видел Card с onClick (navigate), но также link "Подробнее" внутри. Visual: user видит два CTAs. Этого я не учёл в моей UI-CRITIQUE — это новая проблема L-16.

3. **СберСпасибо widget в Dashboard** — это P1-17 ship но он расположен в Dashboard главной странице, занимая 33% column. Это actually intrusive — Dashboard hero + 4 sub-metrics + Spasibo widget = много для одного экрана.

4. **Admin OTC > admin Dashboard** — OTC выглядит более professional чем Dashboard. Dashboard layout 4+4+2 чувствуется как "fit everything I have", OTC чувствуется как "purpose-built screen".

---

## Action items

1. **Redeploy** running containers с current `claude/elated-elgamal-dba521` HEAD — закроет ~15 из 38 findings (все would-fix marked items)
2. **Fix seed data** — pervasive scale issue (L-01 / L-24 / L-25 / L-28 / L-33) — самый высокий ROI fix
3. **Sprint 13 sweep** на новые findings L-03 / L-06 / L-11 / L-14 / L-35 — все они HIGH severity без deploy fix
4. **L-04 / L-16 / L-30 / L-32** — re-organise pages где info architecture можно улучшить (СберСпасибо placement, pool card double-CTA, admin tx token context, admin pools unit consistency)

---

*Sign-off: 11 экранов captured (7 user + 4 admin) + 38 new findings
documented + cross-referenced с UI-CRITIQUE-2026-05-22 предыдущий
backlog. Дата: 2026-05-22.*
