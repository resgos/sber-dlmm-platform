# UI design critique — 2026-05-22

> Самостоятельный design review после Sprint 11+12 wave commit `5301216`.
> Тон: honest senior product designer на discovery interview — не
> flattery, конкретные находки с локациями + рекомендации.
> Author: Claude · 2026-05-22.

---

## Тренда — "Платформа функционально богатая, но *визуально устаёт*"

За последние 4 сессии напихали:
- 4 store'а в Profile (Theme + Language + KYC + AutoClaim + 2FA + Team CTA)
- 2 watcher'а на PositionsPage (Alerts + AutoClaim) + Health column + Segmented фильтр + Bell + 2 mass-action button'а
- 3 страницы добавлены (Rebalance, PoolCompare, Team) в навигацию

Платформа делает много, но **информационная плотность опередила
визуальную дисциплину**. Ниже — 12 конкретных проблем, отсортированных
по влиянию.

---

## #1 — Хаос typography scale (HIGH, structural)

**Locations:** все pages (.tsx).

**Грубые цифры:** 14 различных inline `fontSize` values across codebase:
10 / 11 / 12 / 13 / 14 / 15 / 16 / 18 / 20 / 22 / 24 / 26 / 28 / 32.

Это **в 3+ раза больше** чем должно быть в design system. Канон —
6–8 размеров, привязанных к семантическим ролям.

**Сравнение:** Plasma (источник наших токенов) определяет ровно 7
text scale tokens — `text-caption-xs` через `text-display-2xl`.

**Рекомендация:**

```css
/* sber-theme.css — add type scale tokens */
:root {
  --text-xs: 11px;       /* captions, metadata */
  --text-sm: 13px;       /* body small, table cells */
  --text-base: 14px;     /* default body */
  --text-md: 16px;       /* card titles, key metrics */
  --text-lg: 20px;       /* page subtitles, dialog headers */
  --text-xl: 28px;       /* page titles */
  --text-display: 48px;  /* hero numbers (already exists at .sber-hero-value) */
}
```

Затем sweep — заменить inline `fontSize: 12` → `fontSize: 'var(--text-sm)'`.
ESLint rule `no-magic-fontsize` в hex-ratchet manner.

**Impact:** users подсознательно "читают" hierarchy быстрее когда
сетки знакомы. Сейчас каждая страница немного "своя" — visual jet lag.

---

## #2 — PositionsPage extra-row катастрофически перегружен (HIGH)

**Location:** `pages/PositionsPage.tsx:293` Card extras

Сейчас в `extra={}`:
1. Segmented filter (Все / Отлично / Хорошо / Так себе / Плохо) — 5 chip'ов
2. Bell + count badge ("Алерты (3)")
3. "Забрать всё" primary ghost button
4. "Закрыть всё" danger button

Это **4 интерактивных элемента + 5 chip'ов = 9 hit targets в одной
горизонтальной строке**. На viewport 1200px помещается. На 1024px —
wrap → 2 строки, неравномерно. На phone — chaos.

И семантически — Segmented filter это **навигация** (фильтр данных
в таблице), а bell + 2 кнопки — **действия** (открыть drawer / mutate
state). Они НЕ должны жить в одной зоне.

**Рекомендация:**

```
┌─────────────────────────────────────────────────────────────┐
│ Позиции                       [Алерты (3)] [Забрать] [Закр] │  ← extras = actions ONLY
├─────────────────────────────────────────────────────────────┤
│ Все · Отлично · Хорошо · Так себе · Плохо                   │  ← own row above table, filter zone
├─────────────────────────────────────────────────────────────┤
│ table…                                                      │
```

Конкретно — extract Segmented в собственный `<Card.Section>` или
просто div выше Table, оставить Card extras только action button'ам.

**Impact:** уменьшает visual noise + хорошо адаптируется на mobile.

---

## #3 — DashboardPage hero доминирует над actionable controls (HIGH)

**Location:** `pages/DashboardPage.tsx` + `.sber-hero` в sber-theme.css

`.sber-hero` имеет `padding: 36px 40px`, hero value 48px font, animated
gradient 18s shift, layered box-shadow + radial overlays. Это **визуальный
магнит**.

Под ним — 3 narrow карточки (Spasibo + Quick actions + Recent activity).
По визуальной массе hero занимает ~60% above-the-fold real estate.

**Что НЕ работает:** user первые 2 секунды смотрит на hero (это
правильно). Потом — на actions (тоже правильно). НО actions выглядят
**секондари** относительно hero. На самом деле для регулярного
визита actions важнее ("что мне делать сейчас?"), а hero — лишь
context ("сколько у меня").

**Рекомендация — две тактики:**

A) **Менее тактично**: уменьшить hero padding до 24px на desktop +
   value font до 36px. Этого достаточно чтобы actions/quick links
   получили equal visual weight.

B) **Стратегически**: rebalance information hierarchy. Hero становится
   `40% height, 60% width` (left column), Quick actions становится
   `40% height, 40% width` (right column) — equal visual weight,
   user видит "вот сколько у меня + вот что сделать" одним взглядом.

**Impact:** repeat-visit UX (где hero — повторяющийся context, не
discovery момент) значительно улучшается. Treasurer'ы заходят 10+ раз
в день — каждый раз hero отвлекает на свою презентацию.

---

## #4 — Слишком много зелёных оттенков (MEDIUM)

**Location:** `sber-theme.css:4-9`

```css
--sber-green: #21A038;
--sber-green-hover: #1C8A30;
--sber-green-light: #E8F5E9;
--sber-green-dark: #0E6B1E;
--sber-green-vibrant: #00C853;
--sber-green-deep: #006B3F;
--sber-aqua: #00B5A1;     ← greenish-blue
```

Семь вариантов "Sber green". Использование вырывает:
- `--sber-green` — primary CTA
- `--sber-green-hover` — hover state
- `--sber-green-light` — bg tints, success Alert
- `--sber-green-dark` — sometimes text on white backgrounds, sometimes secondary CTA
- `--sber-green-vibrant` — gradient stop
- `--sber-green-deep` — gradient stop
- `--sber-aqua` — gradient stop

Из 7 — три (vibrant, deep, aqua) встречаются **только в hero gradient**.
Остальные используются inconsistent: `sber-green-dark` иногда для
текста, иногда для hover, иногда для primary winners в Pool comparator.

**Рекомендация:** свести к 4 brand greens с явными ролями:
```css
--brand-primary: #21A038;       /* CTA, brand mark, primary text accents */
--brand-primary-hover: #1C8A30; /* hover only */
--brand-primary-soft: #E8F5E9;  /* bg tints, success alerts */
--brand-primary-strong: #0E6B1E;/* secondary CTAs, "winners" highlights */
```

`-vibrant / -deep / -aqua` оставить как PRIVATE gradient stops (с
именами `--grad-stop-1/2/3` и не использовать вне `.sber-hero`).

**Impact:** visual cohesion + новые разработчики сразу понимают какой
цвет когда применить.

---

## #5 — Border-radius scale fragmented (MEDIUM)

**Locations:** все .tsx

**Inline `borderRadius` distinct values:** 2, 6, 8, 10, 12, 16, 999.

`--radius-sm/md/lg/xl` в theme = 8/12/18/28 — определены, но
не везде использованы. Inline 6 и 10 (Login form Inputs, например)
— не из системы.

**Рекомендация:** sweep — заменить inline `borderRadius: 8` →
`borderRadius: 'var(--radius-sm)'`. ESLint rule по аналогии с
hex-ratchet. Удалить radii которые не в scale (6, 10, 16 must
become 8, 12, 18).

**Impact:** angular language страницы становится консистентным.
Сейчас Inputs, Cards, Tags визуально немного разные shapes.

---

## #6 — ProfilePage right rail слишком тяжёлый (MEDIUM)

**Location:** `pages/ProfilePage.tsx` after my Sprint 11 commits

После всех вёрсток ProfilePage right rail теперь содержит:

1. Сводка по аккаунту (Card)
2. Последняя активность (Card с 5 transaction rows)
3. **TwoFactorSettings** (Card — мой Sprint 11 ship)
4. **AutoClaimSettings** (Card — Sprint 10 ship)
5. ThemeToggle (Card)

5 cards подряд в `<Space direction="vertical" size={24}>`. Это **~1200px
vertical scroll** на desktop just для right rail. Mobile — 5 cards
stacked под основным контентом left column → **бесконечная страница**.

**Проблема:** Profile — это место "где у меня настройки". Сейчас это
"где у меня настройки И мини-дашборд И recent activity И тёма И 2FA".
Слишком много функций на одной странице.

**Рекомендация:** разнести на 2 экрана:
- **`/profile`** — identity (name/email/KYC) + Recent activity
- **`/settings`** (новый) — Theme + Language + 2FA + AutoClaim + Team
  (Sprint 11 G-21 страница как tab внутри)

Сейчас `/settings` уже занят admin-side. На user-side можно сделать
nested tabs: `/profile/identity` + `/profile/security` + `/profile/preferences`.

**Impact:** finding-things-quickly улучшается; user не скроллит чтобы
найти 2FA toggle.

---

## #7 — Button size distribution разорвана (LOW-MEDIUM)

**Counts across codebase:**
- `size="small"`: 58 occurrences
- `size="large"`: 20 occurrences
- `size="middle"`: 15 occurrences

`small` в 4× чаще `middle`. Это значит "small" по факту наш default,
а official AntD default — `middle`. Мешает (а) дев-у понять "когда
small?", (б) accessibility (44px tap target минимум по AppleHIG/
MaterialDesign — `small` AntD = 24px высота).

**Рекомендация:**

1. Установить convention в README или CLAUDE.md:
   - `large` — primary CTAs в forms / wizard finish
   - `middle` (default) — secondary actions
   - `small` — table cell actions, tag controls
2. ESLint rule предупреждает на `size="small"` если внутри table —
   проверять.

**Impact:** менее серьёзная проблема, но Dmitry на demo заметит
"какие-то кнопки маленькие, какие-то нормальные".

---

## #8 — PoolComparePage card overflow at 3 pools (MEDIUM)

**Location:** `pages/PoolComparePage.tsx` ProMetricsBlock

С 3 pools — каждая card получает `xs={24} sm={12} md={8}` колонку.
На md (768px+) это даёт ~256px per card. Внутри: 5 Statistic с title +
value + наш новый ProMetricsBlock с 3 строками + Open button. Это
**11 рядов информации в 256px ширины и ~440px высоты**.

Statistic'и сжимаются — titles обрезаются ("Объём 24ч" → "Объём 2…"),
"Шаг бина" tooltip icon выходит на новую строку.

**Рекомендация:**

A) Минимум — increase Statistic title `fontSize: var(--text-xs)`
   (11px) явно, не наследоваться.
B) Лучше — переключить layout с **3-up cards** на **comparison table**
   (rows = metrics, cols = pools). Таблица всегда compact, scroll
   horizontally на mobile.

```
                  SBER/SRUB    GAZP/SRUB    USDT/SRUB
APY               18.5%        14.2%        22.1% ✓
Volume 24h        1.2M         900K         3.4M ✓
TVL               5.0M         4.2M         8.1M ✓
Base fee          0.30%        0.50%        0.05% ✓
Bin step          0.25%        0.50%        0.10% ✓
30d vol (synth)   12.4%        15.1%        8.2%
Max drawdown      −6.1%        −9.3%        −3.0%
Sharpe            +0.42        +0.01        +1.20 ✓
```

Visual scan слева направо мгновенный. Победители сразу видны в
"строке-метрике". Compact на mobile.

**Impact:** institutional users (Dmitry, АВ) reading 5+ metrics
сравнения предпочитают tabular view; visual-shopper users (Anna) — cards.
Поддержать оба через toggle "Карты ↔ Таблица".

---

## #9 — Empty states inconsistent (LOW)

**Locations:** PoolsPage, PositionsPage, PoolComparePage, TeamPage

Используются 3 разных pattern:
- `<Empty description="..." />` — default AntD
- `<Empty image={Empty.PRESENTED_IMAGE_SIMPLE} ... />` — simpler illustration
- `<Alert type="info" message="..." />` — for "no data filtered"

**Рекомендация:** один компонент `<EmptyState illustration="x" cta={...} />`
который standardizes:
- Default illustration (simple light line drawing)
- Required `description` prop
- Optional `cta` prop (Button)
- Optional `secondary` prop (link)

**Impact:** consistency + easier для product designer'а позже
поменять illustrations.

---

## #10 — Loading states fragmented (LOW)

**Locations:** все pages

Использования:
- `Skeleton active paragraph={{ rows: 3 }}` (PoolsPage cards)
- `<Spin>` (some Modals)
- Button `loading` prop (action buttons)
- React Query `isLoading` → ничего не показывает (просто пустое state до
  data load) — наш самый частый pattern

**Что плохо:** на slow connection user видит **пустой dashboard** ~1-2 сек
без визуального feedback что "загрузка идёт". Skeleton это решает,
но мы его применяем только в одном месте.

**Рекомендация:** Skeleton everywhere где есть React Query без
fallback. Один helper `<SkeletonCard rows={3} />` + sweep вызовов
`{isLoading ? <SkeletonCard /> : data...}`.

**Impact:** perceived performance улучшается значительно. Особенно
на дешёвых mobile networks.

---

## #11 — Sidebar density растёт (LOW)

**Location:** `components/UserLayout.tsx`

Menu items сейчас (после Sprint 11):

```
Главная
Обмен
Хедж FX
Пулы
Мои позиции
Ребаланс           ← Sprint 10
Команда            ← Sprint 11
Транзакции
Профиль
```

9 пунктов. На viewport >=768px нормально. Но при collapsed sidebar
(64px), уже 9 icon'ок подряд — visual repetition.

**Рекомендация:**
- Group sidebar items в 2 секции с разделителем:
  - **Торговля**: Главная, Обмен, Хедж FX, Пулы
  - **Управление**: Мои позиции, Ребаланс, Транзакции, Команда
  - **Аккаунт**: Профиль
- AntD `<Menu>` поддерживает `<Menu.ItemGroup>` для этого

**Impact:** scanability + готовность к добавлению 10+ пунктов в Sprint
12+ без overcrowding.

---

## #12 — Modal'ы не имеют consistent header pattern (LOW)

**Locations:** Position remove Modal (LiquidityPage), Team create Modal
(TeamPage), Team invite Modal (TeamPage), 2FA setup (TwoFactorSettings),
Auto-claim regenerate codes (TwoFactorSettings), OTC quote (admin)

Headers сейчас:
- Some: plain title text ("Удаление ликвидности")
- Some: title + icon ("⚠️ Включение 2FA")
- Some: title + status tag ("Новые резервные коды [warning]")

**Рекомендация:** одна шаблонная функция `<ModalHeader title icon? severity? />`
со стандартами:
- info Modal — icon left + title
- danger Modal — red icon + title + maybe red border-top на содержимом
- success Modal — green checkmark + title

**Impact:** user быстрее распознаёт "что я сейчас делаю — это
безобидная настройка или потенциально опасное действие?"

---

## Что НЕ проблема (positive observations)

1. **Plasma tokens use** — действительно следуем Sber design system,
   не "Bootstrap defaults". Это видно в хорошем выборе spacing scale
   (8/12/16/24).

2. **Dark mode** после wave 3 polish — реально работает, не "inverted
   light". Hero десатурируется, AntD overrides комплектны.

3. **Hex-ratchet + UI shared-code drift watchdog** — это real design
   discipline в CI. Большинство дизайн-систем не имеют такого.

4. **HealthScoreExplainer** — one-shot onboarding banner — правильный
   pattern. Не permanent help text, не overlay tutorial. Точечно.

5. **Touch-friendly affordances** — добавили ⓘ chevron к
   HealthScoreBadge, Tooltip+Popover combo на multiple components.
   Хорошее внимание к non-mouse users.

6. **Иconography через @ant-design/icons** — единый source, не миксуем
   Lucide / Phosphor / Heroicons. Это redeems много design-debt.

---

## Приоритизация фиксов

| # | Issue | Impact | Effort | When |
|---|---|---|---|---|
| **#2** | PositionsPage extras chaos | HIGH | S (1d) | Now |
| **#1** | Typography scale | HIGH | M (2d) | Sprint 12 |
| **#3** | Dashboard hero dominance | HIGH | S (0.5d quick) / M (2d strategic) | Sprint 12 |
| **#6** | ProfilePage right rail overload | MEDIUM | M (2d — split routes) | Sprint 12 |
| **#8** | PoolComparePage 3-card overflow | MEDIUM | S (1d toggle) | Sprint 12 |
| **#4** | Зелёный palette consolidation | MEDIUM | S (1d sweep) | Sprint 13 |
| **#5** | Border-radius scale sweep | LOW | S (1d sweep + lint) | Sprint 13 |
| **#7** | Button size convention | LOW | S (doc + sweep) | Sprint 13 |
| **#11** | Sidebar grouping | LOW | XS (0.5d) | Sprint 12 |
| **#9** | Empty states unify | LOW | S (1d component + sweep) | Sprint 13 |
| **#10** | Loading states sweep | LOW | M (1.5d) | Sprint 13 |
| **#12** | Modal header pattern | LOW | S (1d) | Sprint 13 |

---

## Now-now fixes (ship in next commit if possible)

- **#2** — PositionsPage extras разнести (Segmented filter выше Table,
  actions остаются в Card.extras). 1 file change, ~15 minutes.
- **#11** — Sidebar grouping через Menu.ItemGroup. 1 file change, ~10
  minutes.

Если позволишь — пушну эти 2 в следующий коммит как proof-of-concept
для design discipline reset. Бóльшие проблемы (#1, #3, #6) требуют
designer's hand и согласования с marketing.

— Claude · 2026-05-22
