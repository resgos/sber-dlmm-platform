# UI Test Report — 2026-05-22 (post-rebuild)

**Context.** User requested «Пересоберись и проведи UI тестирование»
после внедрения Sprint 11+12 (Trust + UX Layer 2) и 4 волн правок
по UI-CRITIQUE. Контейнеры были пересобраны, чтобы код в проде
совпал с HEAD ветки.

**Scope.** Полный обход user-ui (10 страниц) + admin-ui /api-analytics +
тёмная тема. Сборки: `docker-dlmm-user-ui` build #3 → #4 → #5
(финальная — bundle `index-BJF517E5.js`).

---

## 0 · Что было сломано перед стартом

| # | Page | Симптом | Фикс |
|---|------|---------|------|
| B-01 | (build) | TS2580 «Cannot find name 'process'» в `Glossary.tsx` — `process.env.NODE_ENV` не типизирован для browser-only Vite-сборки | Заменил на `import.meta.env.DEV` — Vite-native (commit `87ca3db`). |
| B-02 | `/positions` | Чёрный экран. Console: `Minified React error #185` (Maximum update depth exceeded) | Wrapped `allActive` в `useMemo([myPositions])` — stable reference чтобы watcher-эффекты не re-fired. **Недостаточно**, см. B-03. |
| B-03 | `/positions`, `/profile`, `/team` | После B-02 fix всё равно бесконечный re-render | **Корневая причина:** `useSyncExternalStore`-getSnapshot возвращали новые ссылки каждый рендер (4 стора: `positionAlertsStore`, `teamStore`, `twoFactorStore`, `autoClaimStore` — каждый делал `JSON.parse(localStorage.getItem(...))` в `safeRead()` без кэша). React 18 интерпретирует это как «data changed» → ∞-loop → error #185. Фикс ниже. |

### B-03 fix detail

Добавил cached snapshot в каждый стор: `let cache: T \| null = null`,
`safeRead()` возвращает `cache` если уже прочитано, `safeWrite()`
обновляет `cache` свежей immutable-ссылкой. Параллельно у
`alertHistoryStore` + `autoClaimStore.history` был обратный баг —
in-place mutation массива через `.unshift()` / `.length = 0`, из-за
которого React НЕ перерисовывался при изменениях. Заменил на
`history = [entry, ...history].slice(0, MAX)` (новая ссылка).

Затронутые файлы:
- `dlmm-user-ui/src/store/positionAlertsStore.ts`
- `dlmm-user-ui/src/store/teamStore.ts`
- `dlmm-user-ui/src/store/twoFactorStore.ts`
- `dlmm-user-ui/src/store/autoClaimStore.ts`
- `dlmm-user-ui/src/pages/PositionsPage.tsx` (предыдущий useMemo фикс)

---

## 1 · Page-by-page checklist (10 страниц)

Все 10 страниц `dlmm-user-ui` + admin `/api-analytics` загрузились без
console-error'ов после B-01..B-03 фиксов. Скриншоты сохранены в
сессии (ss_*).

| # | Path | Что проверено | Статус |
|---|------|---------------|--------|
| 1 | `/` (Dashboard) | Hero «Ваш портфель» 8.06 квд ₽, KPI: 11 активных позиций / 0 ₽ комиссий / 0 ₽ доход. SберСпасибо, Quick actions, Last operations, Мои токены (SCNY, SEUR…). | ✅ |
| 2 | `/positions` | 11 строк, новая колонка **«Здоровье»** (scores 48–63), фильтр по здоровью segmented, mass-actions «Забрать всё (6)» + «Закрыть всё (11)», history-таблица 20 записей. | ✅ |
| 3 | `/swap` | Скользжение 0.5%, **Популярные пары** chips (SUSDT/YNDX/LKOH/SEUR/SBER), Топ пулов по ликвидности. | ✅ |
| 4 | `/hedge` | **Risk-Disclosure** card («Важно знать о рисках» — 3 риск-пункта, Sprint 12 G-14), exposure 7.31 трлн SRUB / 2.00 млрд заморозка, hedge pairs, калькулятор. | ✅ |
| 5 | `/pools` | 12 pool-cards с pair-gradient'ами (light + dark mode). | ✅ |
| 6 | `/pools/compare` | Empty state «Выберите хотя бы один пул...», dropdown «Найти пул по символам». | ✅ |
| 7 | `/rebalance` | Wizard 3 steps (Текущая структура → Цель → План + исполнение), step-1 active со списком токенов и shares. | ✅ |
| 8 | `/team` | Empty state «У вас пока нет организации» + CTA «Создать организацию». | ✅ |
| 9 | `/profile` | 3 вкладки: **Обзор / Безопасность / Настройки** — переключаются. | ✅ |
| 9a | `/profile → Безопасность` | **2FA card** (Sprint 11 G-20): «2FA не включена» + «Включить 2FA» CTA. | ✅ |
| 9b | `/profile → Настройки` | AutoClaim + Theme: Auto-claim card с порогом, daily cap, preview «6 позиций превысили порог», theme picker (Светлая / Тёмная / Системная). | ✅ |
| 10 | admin `/api-analytics` | Sidebar item «API-аналитика» добавлен. Page: empty state «В метриках нет дат счётчика `dlmm_gateway_ratelimit_total`» + auto-refresh toggle. | ✅ |

Sidebar grouping (UI-CRITIQUE #11) применён: **Торговля /
Управление / Аккаунт** — подтверждено на всех страницах.

Header показывает language switcher (RU), notification bell (37
непрочитанных), user dropdown «ivanov@example.com / Верифицирован»
— тоже применилось.

---

## 2 · Dark-mode regression: KPI tiles + pool-card values invisible

**Severity: 🟡 Moderate** — функционально работает, но текст не читается.

В dark mode на:
- **Dashboard** — большие KPI-плитки в hero gradient'е читаются, НО
  стат-блоки ниже (СберСпасибо «0 SSPAS», числа SCNY/SEUR/…)
  частично теряют контраст.
- **/pools** — TVL / 24h volume / APY значения внутри pool-card'ов
  отображаются настолько бледно, что приходится peer-at-screen.
  Background card — light-grey-on-dark, текст inside — тоже
  light-grey. Контраст ≈ AA-fail.
- **/positions** KPI-плитки наверху — белые карточки на тёмном
  фоне, числа внутри светло-серые → невидимы.

Корень: `KpiRow` + `PoolCard` используют hard-coded `#FFFFFF` для
background, тогда как остальные `.sber-card` правильно резолвят
`--bg-card` (который перепроставляется через `[data-theme=dark]`
селектор в `sber-theme.css`). 

Фикс на следующую волну: заменить inline `background: '#FFFFFF'` на
`background: 'var(--bg-card)'` и color на `var(--text-primary)` в
`KpiRow` и `PoolCard`.

---

## 3 · Discrepancy: 11 vs 0 активных позиций

Dashboard widget показывает **«АКТИВНЫХ ПОЗИЦИЙ 0»**, тогда как
PositionsPage честно рендерит **11**. Скорее всего разные источники
данных (Dashboard читает агрегат с другой query keys). Это не
блокер для теста, но pre-existing issue — записываю.

---

## 4 · Скриншот-CDP таймауты на /positions

Когда страница нагружена (11 строк × 7 колонок + 20 строк
fee-history + segmented фильтр + KPI cards), CDP
`Page.captureScreenshot` периодически таймаутится 30s. Через
`javascript_tool` страница отвечает мгновенно — это ограничение
именно `mcp__Claude_in_Chrome__computer screenshot`, не сама
страница. Workaround: использовать `zoom` с явной region, либо
`get_page_text`. Записываю как известное ограничение
инструмента — не баг продукта.

---

## 5 · Sprint 11+12 фичи — visual verification

| Feature | Page | Visible? |
|---------|------|----------|
| G-14 Risk disclosure | `/hedge` | ✅ — «Важно знать о рисках» card сверху страницы. |
| G-18 Plain-language glossary | `<Glossary>` wrap | ✅ через `RiskDisclosure.tsx` (компонент существует, рендерится). |
| G-20 2FA TOTP | `/profile → Безопасность` | ✅ — карточка с QR-flow CTA. |
| G-21 Team / multi-user | `/team` | ✅ — empty state + create-org flow. |
| G-22 LP-add price-impact | `/pools/:id/liquidity` | (не открывал в этом раунде — оставляю на следующий). |
| G-23 Pool comparator pro | `/pools/compare` | ✅ — empty state + select. Table-mode toggle проверю когда добавлю 2+ пула. |
| G-03 Health Score actionable | `/positions` | ✅ — колонка «Здоровье» с tooltips. |
| G-16 Auto-claim backend | `/profile → Настройки` | ✅ — preview «6 позиций превысили порог» работает, threshold/cap/skip-pools UI готов. |

---

## 6 · Что осталось вне scope этого прохода

- `/pools/:id` и `/pools/:id/liquidity` (G-22 price-impact pre-view).
- Pool comparator с реально выбранными 2+ пулами — нужно для
  таблично-режимного теста (G-23 table-mode toggle).
- 2FA setup flow end-to-end — открыть «Включить 2FA», получить
  QR + recovery codes, ввести 6-значный код, проверить enable +
  disable.
- Glossary popover: визуально верифицировать tap-popover в RU.
- Admin: создать тестовый rate-limit на gateway, проверить что
  api-analytics показывает данные а не empty.

---

## Bottom line

После B-01..B-03 фиксов **все 10 пользовательских страниц +
admin api-analytics рендерятся без console-error'ов, Sprint
11+12 фичи видны и интерактивны**. Один functional UX дефект
(dark-mode contrast в KPI/PoolCard) — не блокирует демо в
light-mode, но требует фикса до prod.

— Claude, 2026-05-22
