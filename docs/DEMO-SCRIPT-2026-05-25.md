# Demo Script — Sber DLMM Platform (2026-05-26)

**Target audience:** PO, IT-lead, system analyst, demo-day
**Duration:** 10–15 минут walkthrough + 5 минут Q&A
**Build:** `claude/elated-elgamal-dba521` HEAD = `79710b2` (после Batch #2 merge)
**Screen res:** 1440×900 (typical demo screen)
**Credentials:**
- User: `ivanov@example.com` / `Demo1234`
- Admin: `admin@sber-dlmm.ru` / `Demo1234`

---

## Pre-flight (за 5 минут до демо)

```bash
# 1. Бекап-проверка что стек запущен
docker ps | grep dlmm | wc -l   # expect: ~17 containers

# 2. Бекап-токен — должен возвращать accessToken
curl -sX POST http://localhost:8080/api/v1/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"email":"ivanov@example.com","password":"Demo1234"}' | jq -r .accessToken | wc -c
# expect: > 100 chars

# 3. Browser cache cleared (Ctrl+Shift+R)
# 4. Открыть две вкладки:
#    - http://localhost:3001/  (user UI)
#    - http://localhost:3000/  (admin UI)
# 5. Прогнать walkthrough один раз — выявить laggy pages
```

---

## Walkthrough

### Act 1 — Personal experience (≈4 min)

**1. Login page (30s)**
- Open `http://localhost:3001/#/login`
- "Это вход в платформу управления ликвидностью для корпоративных казначейств. Поддерживает email/пароль и корпоративный SSO (Azure AD / Sber Federation)."
- **Show:** "Войти через корпоративный SSO" button (S14-01 scaffolding)
- Click "Войти" с email/password → land on Dashboard

**2. Dashboard (45s)**
- "Это рабочее место казначея. Видны: общий портфель (≈8 квдр ₽), 11 активных позиций, последние операции, баллы СберСпасибо за активность."
- **Hover** key tiles to show tooltips
- **Show:** "Мои токены" таблица — баланс SBER / SRUB / LKOH

**3. Live theme toggle (R-05) (15s)**
- Profile menu → Settings → Theme: Light → Dark
- "Тема переключается мгновенно без перезагрузки — это нативная поддержка AntD Design tokens, никаких CSS-хаков"
- Switch back to Dark (демо часть лучше в темной)

**4. Pool detail + Liquidity (G-22 preview) (60s)**
- Navigate /pools → click "SRUB/LKOH"
- "Это страница пула. Видны цены, объёмы, бин-распределение (это сердце Liquidity-as-a-Market-Maker)."
- Click "Добавить ликвидность"
- Fill X=100000 / Y=200 → "Смотри preview-панель — TVL share, in-range chip, предупреждения о price-impact"
- "Это G-22 preview — даёт казначею full transparency ДО подписи транзакции"

**5. Positions + Health Score + Auto-claim (45s)**
- Navigate /positions
- "Health-score badges — 0–100, зелёный = здоровая, оранжевый = warning, красный = out-of-range"
- Click "Забрать всё" — mass-action для bulk-fee-collection
- Profile → Auto-claim — "Пороги, дневной лимит, исключение пулов — backend-driven с server-side scheduler"

### Act 2 — Security (≈2 min)

**6. 2FA setup (R-02) (60s)**
- Profile → Безопасность → "Включить 2FA"
- **CRITICAL:** modal показывает РЕАЛЬНЫЙ 32-char secret + QR (не PENDING)
- "Это TOTP-flow, работает с Google Authenticator / Authenticator / Яндекс.Ключ"
- Cancel — но проверили что модал реален

**7. Multi-user + Team (G-21) (45s)**
- /team → "Test Org" с 2 members
- "Compliance — каждое действие пользователя ассоциировано с org, audit log хранит actor_type=USER (S13-02)"

### Act 3 — Admin perspective (≈3 min)

**8. Logout + Admin login (15s)**
- Logout → log in as admin@sber-dlmm.ru

**9. Admin Dashboard (30s)**
- "Видим TVL по платформе, операционные KPI, топ пулы, последние транзакции."

**10. Cohort analytics (S14-02) (45s)** ⭐ NEW
- Navigate /admin/cohorts
- "DAU / MAU / D7 / D30 retention. Это критичная метрика для PMF — показывает что пилотные клиенты не только пробуют, но ВОЗВРАЩАЮТСЯ."
- Toggle metric: DAU → MAU → D7 → D30

**11. Audit log (S13-02) (30s)**
```bash
docker exec dlmm-postgres psql -U dlmm -d dlmm -c \
  "SELECT action_type, actor_type, COUNT(*) FROM admin_audit_log GROUP BY 1,2 ORDER BY 3 DESC LIMIT 5"
```
- "Compliance — каждое действие админа И пользователя логируется. Это требование Сбер Security."

**12. API analytics (F-15) (30s)**
- /admin/api-analytics — "Rate-limit per endpoint, top abusers, 429 trends"
- "Защита от ddos + защита от пилотных клиентов которые случайно зацикливают integration"

**13. Resilience4j — circuit breakers (S13-01) (30s)**
```bash
curl -s http://localhost:8088/actuator/circuitbreakers | jq '.circuitBreakers | length'
# 6 — per-downstream CB
```
- "Если token-service упал — admin UI не зависает на 10s timeout. CB OPEN → fail fast → пустой ответ + warning."

### Act 4 — Story close (≈1 min)

**14. SAML SSO (S14-01) (30s)**
- Logout → /login → click "Войти через корпоративный SSO"
- "Сейчас scaffolding — 501 с guidance. Spring SAML2 starter активируется в Sprint 15 при подключении IdP. Метадата SP уже доступна по /api/v1/auth/saml/metadata — ops team может зарегистрировать SP в Azure AD НЕМЕДЛЕННО."

**15. Reviews page (S14-03) (15s)**
- /reviews (после re-login) → "4 отзыва пилотных клиентов — Казначей, ФинДир, Риск-менеджер, Главбух. Это social proof для прайс-марketing."

**16. Simple-mode toggle (S14-03) (15s)**
- Profile → Настройки → Simple-mode ON
- "Скрывает advanced items для junior-treasurer: Ребаланс, Команда. UX гибкость без потери power-user features."

---

## Q&A bank

**Q1: А что с Helm / Kubernetes deploy?**
A: Sprint 13 plans Helm chart + Vault prod, blocked external на Sber Cloud infra. Сейчас docker-compose production-ready (restart policies, healthchecks, Resilience4j, audit log).

**Q2: Что если IdP недоступен — пользователи залочены?**
A: SAML — это additional auth path, email/password остаётся primary. R-04 JWT refresh-token flow дает 30-min sliding session — пользователь не выкидывается mid-day на спиннер.

**Q3: Telegram-бот?**
A: Сознательно вне scope. Email уведомления + in-app notification-service покрывают MVP. Telegram оценим после пилота — нужен ли businessу.

**Q4: Сколько LP позиций в пилоте?**
A: Сейчас 11 в seed. Системно tested до 300 VUs (k6 baseline). p99 для /pools < 1s warm.

**Q5: А кохорта-аналитика — на каких данных?**
A: На таблице `transactions.user_id` + `created_at`. JdbcTemplate + DISTINCT + DATE_TRUNC. Reasonable для пилота (< 10k транзакций). Под продуктив переедет на ClickHouse cohort cube (Sprint 15).

---

## Failure recovery (если что-то сломается во время демо)

| Симптом | Fix |
|---------|-----|
| Бесконечный спиннер на любой странице | `localStorage.clear()` → reload → re-login |
| Page blank | Open DevTools → проверить console + Network — скорее всего endpoint down → `docker ps` |
| Dark mode частично сломан | Toggle theme дважды (R-05 + sber-theme.css overlay должен fixнуть) |
| Любой Spring service Exit(1) | `docker logs <service> --tail 50` → грейс рестарт `docker-compose up -d --no-deps <service>` (15s) |
| Postgres connection lost | `docker restart dlmm-postgres` (10s) — это самый страшный — не делать во время демо если можно избежать |

---

*Готово к демонстрации. После завершения — добавить feedback в `docs/POST-DEMO-FEEDBACK.md`.*
