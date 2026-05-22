# UI-CRITIQUE — delivery report

> Применил `design:design-critique` skill (5-dimension framework:
> First Impression / Usability / Visual Hierarchy / Consistency /
> Accessibility) для systematic delivery всех 12 пунктов из
> `docs/design/UI-CRITIQUE-2026-05-22.md`.
>
> Дата: 2026-05-22.

---

## Что shipped

12 / 12 пунктов закрыто в 5 коммитах (`0c761a4` + Waves 1-4 here).

| # | Тема | Wave | Commit |
|---|---|---|---|
| #2 | PositionsPage extras chaos | (prior) | `0c761a4` |
| #11 | Sidebar grouping | (prior) | `0c761a4` |
| #1 | Typography scale tokens | 1A | `021416f` |
| #4 | Brand greens semantic roles | 1B | `021416f` |
| #5 | Border-radius scale tokens | 1C | `021416f` |
| #3 | Dashboard hero rebalance | 2A | `872b9a2` |
| #8 | Pool-compare table mode | 2B | `872b9a2` |
| #6 | Profile right-rail Tabs split | 2C | `872b9a2` |
| #9 | EmptyState unified component | 3A | this wave |
| #10 | SkeletonCard helper | 3B | this wave |
| #12 | ModalHeader pattern | 3C | this wave |
| #7 | Button-size convention doc | 4 | this wave |

---

## Применение 5-dimension framework к каждому fix

### Wave 1A — Typography scale (#1)

| Dimension | Before | After |
|---|---|---|
| **First Impression** | "почему здесь 12px а там 14px?" — невольная микро-загрузка для каждой страницы | One predictable scale → визуальный jet lag убран |
| **Visual Hierarchy** | 14 inline fontSize values → user не учится "большой = важно" | 7 semantic tokens → consistent hierarchy across pages |
| **Consistency** | каждая страница "почти как другие" | Token contract — изменение ladder в одном месте |
| **Accessibility** | 10–11px иногда failed WCAG AA on dark | 11px floor with proper contrast |
| **Usability** | Не влияет напрямую | Косвенно — improved scan-speed |

### Wave 1B — Brand greens (#4)

| Dimension | Before | After |
|---|---|---|
| **First Impression** | OK (зелёный отчётливо Сбер) | OK (без regression) |
| **Visual Hierarchy** | `--sber-green-dark` = и hover, и "winner", и secondary CTA → user не учится правилу | `--brand-primary-hover` (только hover), `--brand-primary-strong` (winners + secondary) — единый словарь |
| **Consistency** | 7 шейдов с overlapping use | 4 semantic + 3 private gradient |
| **Accessibility** | Contrast OK | Без regression |
| **Usability** | — | Дев-у проще выбрать "какой зелёный" |

### Wave 1C — Border-radius (#5)

| Dimension | Before | After |
|---|---|---|
| **First Impression** | "Inputs скруглены так, Cards так" — angular language drift | One angular language across components |
| **Visual Hierarchy** | — | — |
| **Consistency** | 7 distinct radii in scope of 4 tokens | Tokens enforced; legacy radii alias к ближайшему |
| **Accessibility** | — | — |
| **Usability** | — | — |

### Wave 2A — Dashboard hero (#3)

| Dimension | Before | After |
|---|---|---|
| **First Impression** | "это banking grade" ✓ — но тон чрезмерный | Сдержанней; всё ещё brand-recognisable |
| **Visual Hierarchy** | hero доминировал 60% above-the-fold → actions secondary | hero/actions ~45/55 → equal billing |
| **Consistency** | hero был единственным "big & loud" блоком | Tone harmonised с rest of page |
| **Accessibility** | — | Slower animation = меньше vestibular discomfort для motion-sensitive |
| **Usability** | Repeat visit user видел повторяющуюся "презентацию", не context | Hero как ambient context, actions claim attention |

### Wave 2B — Pool compare table mode (#8)

| Dimension | Before | After |
|---|---|---|
| **First Impression** | 3 cards — visual overload, "сколько здесь чисел?" | View mode toggle — user picks density |
| **Visual Hierarchy** | 11 metric rows × 3 cards = 33 cells без semantic anchor | Tabular mode: rows = metrics (semantic anchor), winners marked Crown |
| **Consistency** | cards mode = home for compare; tabular conflicts with это | Both modes are first-class; localStorage persists choice |
| **Accessibility** | Statistic titles truncated at 256px width | Tabular mode horizontal-scrolls на mobile |
| **Usability** | Institutional users hated card cramping | Both audiences served |

### Wave 2C — Profile Tabs (#6)

| Dimension | Before | After |
|---|---|---|
| **First Impression** | 5 cards stacked = "длинная страница" overwhelming | Tabs = "compact, structured, я знаю где смотреть" |
| **Visual Hierarchy** | All 5 cards equal weight, no grouping by concern | 3 tabs by concern: Обзор / Безопасность / Настройки |
| **Consistency** | Mixed — info cards + control cards + toggle cards в одной колонке | Concerns separated; user mental model совпадает |
| **Accessibility** | ~1200px scroll на desktop | One Tab content fits in viewport |
| **Usability** | Find 2FA toggle = scroll lottery | Tab click → tag → done |

### Wave 3A — EmptyState (#9)

| Dimension | Before | After |
|---|---|---|
| **First Impression** | Different empty illustrations across pages | Unified single illustration → "this is empty state" recognised faster |
| **Visual Hierarchy** | Bare `<Empty>` без title vs `<Alert>` с message → inconsistent emphasis | `<EmptyState>` enforces: optional title (h-md weight), optional description, optional CTA, optional secondary |
| **Consistency** | 3 patterns | 1 component, 1 pattern |
| **Accessibility** | — | — |
| **Usability** | "это просто info или мне что-то надо сделать?" — unclear | CTA prop explicit |

### Wave 3B — SkeletonCard (#10)

| Dimension | Before | After |
|---|---|---|
| **First Impression** | Пустой dashboard на 1-2 сек после login | Skeleton placeholder = "загружается" feedback |
| **Visual Hierarchy** | — | — |
| **Consistency** | Skeleton использовался только в PoolsPage cards | One primitive, one convention: `{isLoading ? <SkeletonCard /> : data}` |
| **Accessibility** | Screen reader не получал loading state | AntD Skeleton ariaLive="polite" |
| **Usability** | Perceived perf на slow connection slow | Perceived perf instant — user видит "сейчас будет" |

### Wave 3C — ModalHeader (#12)

| Dimension | Before | After |
|---|---|---|
| **First Impression** | "это безобидная настройка или потенциально опасное действие?" | Severity icon + colour сразу сигнализирует |
| **Visual Hierarchy** | Title plain text in all modals | Icon + colour stripe → user знает level of consequence |
| **Consistency** | 3 inconsistent hand-built patterns | One component, one pattern, one severity vocabulary |
| **Accessibility** | Pure text — screen reader не получает severity | Icon + visible colour AND aria-label of icon (AntD-native) |
| **Usability** | "Удалить ликвидность" выглядел как "Создать пул" | Danger Modal реально выглядит as danger |

### Wave 4 — Conventions doc (#7 + meta)

| Dimension | Before | After |
|---|---|---|
| **First Impression** | n/a | n/a |
| **Visual Hierarchy** | n/a | n/a |
| **Consistency** | Conventions implicit, not documented | `docs/design/UI-CONVENTIONS.md` makes them explicit |
| **Accessibility** | Touch-target 44×44 floor not documented | Документировано + check list |
| **Usability** | New dev — "какой fontSize?" → ловит inline 13 | New dev — открывает UI-CONVENTIONS.md, копирует token |

---

## Verification (final state)

| | Before this critique pass | After all 4 waves |
|---|---|---|
| Inline `fontSize` variants | 14 | 0 numeric (all via `var(--text-*)`) |
| Border-radius distinct inline | 7 | 0 numeric (all via `var(--radius-*)`) |
| Brand "green" tokens with overlapping use | 7 шейдов | 4 semantic + 3 private |
| Empty-state patterns | 3 | 1 |
| Modal header patterns | 3 | 1 |
| Sidebar menu organisation | 9 flat items | 3 semantic groups |
| ProfilePage right-rail scroll | ~1200px | ~400px per Tab |
| PoolComparePage compactness option | none | cards/table toggle |
| Conventions doc | none | `docs/design/UI-CONVENTIONS.md` |
| Project tests | 180 | **187** (+7 new: EmptyState 4 + ModalHeader 3) |

---

## Что НЕ закрыто (intentionally)

- **#1 typography ESLint rule** — sweep done но enforcement (block
  new inline `fontSize: <number>`) — это Sprint 13 ESLint-config
  работа. Сейчас новые inline px caught code review.
- **#5 border-radius ESLint rule** — same.
- **#4 brand greens cleanup of aliases** — `--sber-green*` aliases
  to-be-removed Sprint 13 after all call sites migrated.

Эти 3 — Sprint 13 enforcement задачи. Сейчас они **documented** в
`UI-CONVENTIONS.md` § 11 как "не в CI, будущая работа".

---

## Cross-impact на других персонажей

Через lens 5-personas review (`docs/demo/SIMULATED-DEMOS-FEEDBACK`):

| Persona | What this design pass addresses |
|---|---|
| Елена (VP) | #3 hero rebalance → "repeat-visit" UX which she does multiple times daily; #12 ModalHeader → confidence when audit-mode reviewing |
| Михаил (investor) | #9 / #10 / #12 → "production-quality polish" signals discipline |
| Anna (ИП) | #1 typography → "не ловит мелкий текст" + #14 (uncommitted: больше icons less text); #9 EmptyState helpful when она зашла впервые без активов |
| Дмитрий (финдир) | #8 table-mode для quick-scan compare; #6 Profile Tabs для finding 2FA quickly |
| АВ (enterprise) | #12 ModalHeader severity для compliance officer review; #9 unified empty for audit clarity |

---

## Sign-off

Все 12 пунктов критики применены. 187 vitest cases pass. Branch
`claude/elated-elgamal-dba521` ready.

— Claude · 2026-05-22 (UI-CRITIQUE delivery)
