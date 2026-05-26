# Video Shooting Script — Sber DLMM Onboarding (3 видео × 2-3 мин)

> G-13 (Sprint 15) shipped as **production-ready brief** instead of
> rendered video. Marketing team / external contractor takes this script
> and records via Loom/OBS/Premiere in 1 day. Video files don't ship
> from engineering — script + storyboard + screen-source notes do.
>
> **Resolution:** 1920×1080, 30fps, MP4 H.264, voice 48kHz mono AAC.
> **Browser for screen capture:** Chrome incognito, window size 1440×900
> centred, theme=dark (Sber brand), zoom 100%.
> **Build SHA:** `claude/elated-elgamal-dba521` HEAD = `ba1400a` (или
> новее на момент записи).
> **Test user:** `ivanov@example.com` / `Demo1234` (роль USER).
> **Test admin:** `admin@sber-dlmm.ru` / `Demo1234` (роль ADMIN).

---

## Видео 1 — "Личный кабинет за 90 секунд"

**Aудитория:** treasurer / финдир / новый пользователь
**Длина:** 90 секунд
**Музыка:** Sber brand soft bed (no vocals), -18 LUFS
**Voice:** мужской / женский нейтральный финансовый тон, темп 130 wpm

| Time | On screen | Voiceover (RU) | English subtitle |
|------|-----------|----------------|------------------|
| 0:00 | Sber DLMM логотип на тёмном фоне | "Sber DLMM — платформа управления корпоративной ликвидностью." | "Sber DLMM — corporate liquidity management platform." |
| 0:05 | Кат к `/login` — крупно email field, пользователь печатает | "Входите через корпоративную почту…" | "Sign in with your corporate email…" |
| 0:10 | Кнопка "Войти" → плавный переход на Dashboard | "…или через единый SSO Sber Federation." | "…or via Sber Federation SSO." |
| 0:14 | Dashboard hero: "Добро пожаловать, Алексей" + KPI tiles (TVL 8 квдр ₽, 11 позиций, СберСпасибо) | "Главная страница — всё рабочее место казначея в одном экране: общий портфель, активные позиции, баллы лояльности." | "Dashboard — your full treasury workspace in one view: portfolio, active positions, loyalty points." |
| 0:25 | Курсор наводится на theme toggle в шапке, кликает → весь UI flippt в light mode | "Тёмная и светлая темы — переключаются мгновенно, без перезагрузки." | "Dark and light themes switch instantly." |
| 0:32 | Кат на `/pools`, скролл по карточкам пулов | "Каталог пулов ликвидности — десятки пар, реальная глубина, прозрачные комиссии." | "Liquidity pool catalogue — dozens of pairs, real depth, transparent fees." |
| 0:40 | Клик на "SRUB/LKOH" → PoolDetail | "Кликаем по паре — видим график цены, распределение по бинам, наши собственные позиции." | "Click any pair — see the price chart, bin distribution, and your own positions." |
| 0:50 | "Добавить ликвидность" → fill form, видна preview-панель справа | "Перед подтверждением — preview: ваша доля от TVL, предупреждения о price-impact, in-range статус." | "Before signing — preview: your TVL share, price-impact warnings, in-range status." |
| 1:00 | Кат на `/positions` — health-score badges, "Забрать всё" | "Раздел Позиции — health-score по каждой LP-позиции, ребаланс одним кликом, mass-collect для комиссий." | "Positions — health-score per LP, one-click rebalance, mass-collect for fees." |
| 1:15 | Кат на `/profile` → Безопасность tab → "Включить 2FA" → modal с QR | "Безопасность — TOTP двухфакторная аутентификация работает с Google Authenticator и Сбер ID." | "Security — TOTP 2FA works with Google Authenticator and Sber ID." |
| 1:25 | Кат на `/team` — Test Org с 2 members | "Multi-user: пригласите коллег, разделите роли OWNER / TRADER / VIEWER, каждое действие в audit log." | "Multi-user: invite colleagues, split OWNER/TRADER/VIEWER roles, every action logged." |
| 1:30 | Final card: "Sber DLMM • lkmadnmd.ru • Demo: scan QR" + QR на стороне | "Попробуйте demo: ссылка в описании." | "Try the demo: link in description." |

**Animation cues:** plain cuts, no transitions. 1 zoom-in (на 0:50 preview-панель). 1 highlight callout (на 1:25 audit log row).

**Edit notes:** Cyrillic + Latin subtitle tracks. Auto-translation OK для остальных языков.

---

## Видео 2 — "Запуск пилотного пула за 5 минут"

**Аудитория:** B2B / token issuer / финансовая компания которая хочет вывести свой токен на DLMM
**Длина:** 180 секунд
**Музыка:** Energetic light Sber brand bed
**Voice:** confident мужской с light "consultant" tone, темп 140 wpm

| Time | On screen | Voiceover (RU) |
|------|-----------|----------------|
| 0:00 | Title card: "Запуск пула на Sber DLMM" + Sber green accent | "Допустим, ваша компания выпускает токен — например, корпоративный stablecoin или fractional бумагу. Покажем, как за 5 минут поднять для него ликвидность." |
| 0:08 | Login as admin → admin dashboard | "Заходим в админку — это интерфейс для финансовых партнёров и B2B issuer-ов." |
| 0:15 | `/admin/pools` → "Создать пул" button → modal с формой | "Создаём пул. Указываем пару — например, ваш токен против Sber Ruble." |
| 0:30 | Fill: tokenX, tokenY, binStep=20, baseFee=30, initial price | "Параметры: bin step — это шаг ценовой сетки. 20 базисных пунктов — стандарт для стабильных пар. Базовая комиссия — 0.30%." |
| 0:50 | Submit → success toast → редирект на новый PoolDetail | "Пул создан. Контракты в Bin-Math движке инициализированы, протокольная комиссия 5% включена." |
| 1:00 | Switch to user view (logout admin → login as ivanov) | "Теперь смотрим, как этот пул выглядит для конечного пользователя." |
| 1:10 | Navigate to new pool → "Добавить ликвидность" | "Первый LP добавляет ликвидность. Стратегия SPOT — равномерно по диапазону. Или CURVE — концентрация у текущей цены для более активного market-making." |
| 1:30 | Filled form, preview panel показывает: TVL share, in-range, no warnings | "Preview-панель показывает, какую долю от пула займёт наша позиция, есть ли price-impact warning, попадаем ли мы в активный бин." |
| 1:45 | Confirm → tx success → редирект на /positions | "Позиция активна, fee tracking запущен, health-score рассчитывается каждые 5 минут." |
| 2:00 | `/swap` — выбираем нашу новую пару, делаем swap | "Делаем тестовый swap через эту пару. Видим — комиссия идёт LP, протокольная доля идёт в treasury Sber." |
| 2:20 | Back to admin → /admin/cohorts | "В админке — кохорта-аналитика по пилотному пулу: DAU, MAU, D7 retention. Эти метрики покажут PMF за первые 30 дней пилота." |
| 2:40 | Кат на `/admin/api-analytics` — показать throttle ratio | "Защита — rate-limiting per tier, observability per route. Видим, какой endpoint клиент бьёт чаще всего." |
| 2:55 | Final card: "Запустить свой пул? sber-dlmm.ru/contact" |  "Готовы запустить? Свяжитесь с командой Sber DLMM." |

---

## Видео 3 — "Архитектура и compliance" (для CIO / Security)

**Аудитория:** CTO / CIO / Security Lead / Compliance
**Длина:** 150 секунд
**Music:** Minimal corporate / no vocals, low energy
**Voice:** Senior tech male, slow + clear, темп 120 wpm

| Time | On screen | Voiceover (RU) |
|------|-----------|----------------|
| 0:00 | Title: "Sber DLMM — Architecture and Compliance" + diagram preview | "Sber DLMM — это микросервисная DeFi-инфраструктура для корпоративных treasury функций." |
| 0:08 | Architecture diagram (статичная): Gateway → 9 microservices → PG / Redis / Kafka / ClickHouse | "9 Spring Boot 3.2.5 микросервисов на Java 21. Единая точка входа — Spring Cloud Gateway с JWT validation, per-tier rate limiting через Redis." |
| 0:30 | Кат: zoom на Gateway block, кросс-фейд на код JwtValidationFilter (статичные строки) | "Каждый запрос валидируется на gateway, JWT с алгоритмом HS256, refresh-token rotation, 2FA через TOTP." |
| 0:50 | Switch to terminal showing prometheus/grafana dashboard live | "Observability: Prometheus + Grafana, 15 dashboards. Каждый сервис expose-it /actuator/prometheus." |
| 1:05 | Кат: `curl http://localhost:8088/actuator/circuitbreakers \| jq` showing 6 CB states CLOSED | "Resilience4j circuit breakers защищают от каскадных отказов — 6 CB per downstream сервис." |
| 1:20 | Кат: terminal `psql ... SELECT action, actor_type, COUNT FROM admin_audit_log GROUP BY 1,2` | "Compliance: каждое действие админа и пользователя в audit log. Соответствие 115-ФЗ, готовность к FinCERT интеграции." |
| 1:35 | Кат: `docker-compose ps` showing 18 containers all Up | "Production-ready stack: Postgres 16 с Liquibase, Kafka с outbox pattern для exactly-once, Redis для cache и rate-limit, ClickHouse для analytics." |
| 1:50 | Diagram: SAML SSO flow к Azure AD / Sber Federation | "Корпоративная аутентификация: SAML 2.0 интеграция с Azure AD и Sber Federation IdP. Готов к подключению пилотного клиента." |
| 2:10 | Кат: Trivy security scan output (или Grafana security dashboard) | "Security baseline: Trivy image scan в CI, weekly. OWASP ZAP скан планируется, контракт с Positive Technologies на pentest." |
| 2:25 | Roadmap slide: текстовый список — "Q3: Helm/k8s • Q4: WAL-G PITR • 2027 H1: НРД custody • H2: AML SAR auto-filing" | "Roadmap прозрачен: Q3 — Kubernetes, Q4 — point-in-time recovery, 2027 — депозитарная интеграция и автоматический comply-report." |
| 2:40 | Final card: "DR drill scheduled quarterly • SLA 99.9% • Sber ecosystem" | "DR drill каждый квартал, SLA 99.9%, ядро Sber-экосистемы." |

---

## Production checklist для contractor

- [ ] Use clean Chrome profile (no extensions, no dev-tools, no bookmarks bar)
- [ ] Window 1440×900 centred (script's reference)
- [ ] Theme = dark (consistent с brand)
- [ ] Cursor highlight enabled (e.g. Cursor Highlighter Chrome ext)
- [ ] Screen capture at 60fps (downsampled to 30fps в edit для smoother cursor)
- [ ] Voice recorded separately в quiet room; sync в post
- [ ] Subtitle files: `.srt` для YouTube/embed, EN + RU separate tracks
- [ ] Export master: ProRes 422 mov • web copy: H.264 mp4
- [ ] Length tolerance: ±5 sec per video (don't exceed scripted)
- [ ] Brand check: Sber green primary (#21A038), Sber dark bg, no foreign palettes

---

## Asset list

Required source assets:
- `docs/SCREENSHOTS-2026-05-26/` — pre-captured screenshots для b-roll fallback (если live capture failed)
- `docs/architecture-diagram.svg` — для Видео 3 (нужно создать, см. backlog item)
- Sber brand kit: logo, colors, fonts (from Sber Marketing portal)
- Music: lookup в Sber Audio Library (3 brand-approved beds for 90s/180s/150s)

---

## Что НЕ снимать (avoid)

- НЕ показывать stack traces / красные warning'и
- НЕ показывать `localhost:` URLs — записывать на staging хост (sber-dlmm-staging.tld)
- НЕ показывать дев-токены, secrets, .env содержимое
- НЕ показывать SAML 501 error — пропустить эту секцию (full integration после Sprint 15)
- НЕ показывать pre-existing tech-debt warnings — фильтровать через `2>/dev/null` или edit out

---

## After-shoot QC

- Screen capture FPS smooth (60→30 без jitter)
- Voice -18 to -14 LUFS dialogue intelligibility
- Subtitle sync ≤ ±200ms drift
- Color check on second monitor (Sber green tonal accurate)
- Length check (no overrun)
- A11y: subtitles burned-in OR softsub option for embed

---

*Этот script — production brief. Не предназначен для прямой публикации.
Marketing team review + brand approval перед записью.
Author: Engineering · Last review 2026-05-26.*
