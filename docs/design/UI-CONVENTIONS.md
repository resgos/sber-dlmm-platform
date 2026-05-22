# UI conventions — `dlmm-user-ui` + `dlmm-admin-ui`

> Установлены в 2026-05-22 после UI-CRITIQUE design pass.
> Замечание: эти конвенции — `living rules`, не сцементированные
> правила. Если конкретный кейс нарушает convention обоснованно —
> добавь комментарий со `// UI-CONVENTIONS-EXCEPTION: <why>` и
> ссылку на RFC обсуждение.

---

## 1. Typography scale

**Source-of-truth:** `sber-theme.css` `--text-*` tokens.

Используй ТОЛЬКО семантические токены, не raw px:

| Token | Pixels | When |
|---|---:|---|
| `--text-xs` | 11px | metadata, captions, "via pool {pair}" |
| `--text-sm` | 13px | body small, table cells, popover content |
| `--text-base` | 14px | default body (AntD default) |
| `--text-md` | 16px | card titles, key metric labels |
| `--text-lg` | 20px | page subtitles, dialog headers |
| `--text-xl` | 28px | page titles (.sber-page-title) |
| `--text-display` | 48px | hero numbers (.sber-hero-value) |

**Inline style:** `fontSize: 'var(--text-sm)'`
**Tailwind-style class:** N/A (no Tailwind on project)

**Anti-pattern:** `fontSize: 12` — найдите ближайший токен и используйте его.

**Exception:** AntD `ConfigProvider` theme tokens **должны** быть
numeric — AntD читает их в JS для расчётов derived sizes. Там
оставлять числа.

**Line-height:**
- `--leading-tight` (1.15) — для display + hero
- `--leading-snug` (1.35) — заголовки
- `--leading-base` (1.5) — body
- `--leading-loose` (1.7) — длинный текст (P, Paragraph)

---

## 2. Brand colours

**Source-of-truth:** `sber-theme.css` `--brand-primary*` tokens.

### Semantic roles

| Token | Hex (light) | When |
|---|---|---|
| `--brand-primary` | #21A038 | primary CTA, brand mark |
| `--brand-primary-hover` | #1C8A30 | hover state ONLY |
| `--brand-primary-soft` | #E8F5E9 | bg tints, success Alert |
| `--brand-primary-strong` | #0E6B1E | "winners" highlights, secondary CTAs |

### Private gradient stops

`--grad-stop-1/2/3` — **только** внутри `.sber-hero` `linear-gradient`.
Не использовать как text colour, border colour, или anywhere else.

### Legacy aliases

`--sber-green` / `--sber-green-hover` / `--sber-green-light` / etc —
живут как `var()` aliases. Новый код пишет `--brand-primary*`. Sprint
13+ — sweep + remove aliases.

### Semantic colour aliases (non-brand)

| Token | Light | Dark | When |
|---|---|---|---|
| `--color-negative` | #DC2626 | #F87171 | P&L negative, slippage warning |
| `--color-negative-strong` | #EF4444 | #FCA5A5 | "Удалить" Modal slider value |
| `--color-warning-amber` | #D97706 | #FBBF24 | swap price-impact warning |

---

## 3. Border radius

**Source-of-truth:** `sber-theme.css` `--radius-*` tokens.

| Token | Pixels | When |
|---|---:|---|
| `--radius-sm` | 8px | buttons, inputs, small cards |
| `--radius-md` | 12px | cards, modals, panels |
| `--radius-lg` | 18px | hero, large containers |
| `--radius-xl` | 28px | (reserved for premium layouts) |
| `--radius-pill` | 999px | tags, status chips |

**Anti-pattern:** `borderRadius: 10` — найдите ближайший token.

---

## 4. Button sizes

| AntD `size` | When | Note |
|---|---|---|
| `large` (default 48px) | primary CTAs в forms / wizard finish | "Подтвердить", "Сохранить", "Войти" |
| `middle` (default 32px) | secondary actions in cards / panels | "Поделиться", "Очистить", "Открыть" |
| `small` (default 24px) | table cell actions, tag controls | DELETE icon, "Имитировать принятие" |

**Anti-pattern:** случайный `size="small"` для primary CTA. На demo
с проектором — кнопка читается с 3 метров; small её топит.

**Touch-target floor:** на mobile все interactive elements должны
иметь минимум 44×44 px tap area (AppleHIG). Small button = 24px →
если только icon, оборачивайте в `<Tooltip>` + `aria-label` и
проверьте touch-сторону отдельно.

---

## 5. Components — choose the existing one

| Need | Use | Don't use |
|---|---|---|
| "No data" / empty list | `<EmptyState>` | `<Empty>` directly, `<Alert type="info">` для no-results |
| Loading placeholder | `<SkeletonCard>` | Manual `<Skeleton>` |
| Modal title | `title={<ModalHeader title="..." severity="..." />}` | Plain text, hand-built icon+text |
| Inline term explanation | `<Glossary term="bin">бин</Glossary>` | `<Tooltip>` со своим текстом |
| Risk disclosure (LP/swap/hedge) | `<RiskDisclosure variant="..." />` | Custom Alert |
| Position health badge | `<HealthScoreBadge position={...} pool={...} />` | Custom Tag с цифрой |

---

## 6. Spacing rhythm

**Source-of-truth:** AntD `Space` `size` prop OR `gap: var(--space-*)`.

AntD `Space` size scale (что используем):
- `4` — внутри inline group (icon + text)
- `6` — внутри tag pile, dense
- `10` — default между siblings в `<Space direction="vertical">`
- `12` — между form fields
- `14` — между cards в right rail
- `16` — между sections in card
- `20` — между rows в page
- `24` — между primary sections (left/right rails, Tabs split)

**Anti-pattern:** случайный `size={7}` или `size={15}` — выбери из
scale.

---

## 7. Iconography

**Source-of-truth:** `@ant-design/icons` ONLY.

- НЕ смешивать с Lucide / Phosphor / Heroicons / emoji
- НЕ использовать `*Filled` версии для decorative — оставить для
  semantic states (success ✓, danger ✗)
- Decorative icons → `aria-hidden`
- Interactive icons (Button icon, clickable) → `aria-label` или
  parent's `aria-label`

---

## 8. Iconography x Severity x Colour

| Severity | Icon | Colour token |
|---|---|---|
| info | `InfoCircleFilled` | `var(--plasma-accent)` |
| success | `CheckCircleFilled` | `var(--brand-primary)` |
| warning | `WarningFilled` | `var(--sber-amber)` |
| danger | `CloseCircleFilled` | `var(--color-negative)` |

`<ModalHeader severity="...">` enforces this mapping.

---

## 9. Dark mode

**Test before shipping**:

- `var(--bg-*)` / `var(--text-*)` / `var(--border-*)` / `var(--surface-*)`
  всё перевернётся автоматически
- Inline hex literals — НЕ перевернутся; use `var(--color-*)` semantic
  alias (см. таблицу ниже).
- Brand greens (`--brand-primary*`) сейчас не имеют dark variants —
  они должны читаться зелёным на обоих фонах.

**Run check:** toggle `<html data-theme="dark">` в DevTools после
любого PR который меняет colours.

---

## 10. Touch + a11y minimum

- Touch target ≥ 44×44 px (Tooltip-only icons OK с aria-label)
- Contrast ratio ≥ 4.5:1 для body text (`<Text type="secondary">` на
  `var(--bg-card)` пограничный — проверять при изменении text-secondary
  alpha)
- Focus ring visible на `--brand-primary` (sber-theme.css strap уже
  есть на `.sber-skip-link:focus`)
- `aria-label` обязателен для icon-only Button

---

## 11. CI gates

Эти rules сейчас в CI (`scripts/check-no-hex-in-tsx.mjs`,
`scripts/check-ui-shared-drift.mjs`):

1. **hex-ratchet** — фактический baseline 274 inline hex. Любой
   новый `#xxxxxx` blocks PR. Fix — используй CSS var token.
2. **ui-shared-drift** — admin-ui + user-ui shared files (KpiTile,
   TokenPairChip, formatters) byte-identical. Fix — sync or document
   the divergence.

Не в CI (future Sprint 13):
- typography token enforcement (block `fontSize: <number>` if not in
  scale)
- border-radius token enforcement
- semantic colour alias enforcement

---

## 12. When in doubt

1. Найди existing component → используй его (`<EmptyState>`,
   `<SkeletonCard>`, etc).
2. Найди existing CSS var → используй её.
3. Не нашёл → спроси в #design slack ИЛИ добавь в это документ
   secondary section + один RFC bullet.
4. Анти-pattern: не пиши свой шестой fontSize value.

---

*Living document. Updated после каждого design pass.*
*Last updated: 2026-05-22 (UI-CRITIQUE wave 4).*
