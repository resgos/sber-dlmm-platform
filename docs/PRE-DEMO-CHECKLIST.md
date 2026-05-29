# Pre-Demo Checklist (updated 2026-05-27)

**Build:** `claude/elated-elgamal-dba521` (latest HEAD)

Run этот чеклист за 10–15 минут до live demo. Если хоть один пункт falls — задержать демо и починить.

---

## ⚠️ STEP 0 — FRESH FRONTEND DEPLOY (critical, learned 2026-05-27)

`docker-compose build dlmm-{user,admin}-ui` intermittently does NOT update the
served bundle in this environment (flaky daemon — exits 0 but image unchanged).
Symptom: admin sidebar missing **Когорты / Здоровье пилотов**, /pilots redirects
to /dashboard, KPI tooltips absent, portfolio shows old numbers. ALWAYS run after
`docker-compose up`:

```bash
bash scripts/redeploy-frontends.sh   # builds dist on host, docker cp into nginx
```

Verify both bundles are fresh:
```bash
# admin-ui MUST contain pilots:
JS=$(curl -s localhost:3000 | grep -oE 'index-[A-Za-z0-9_]+\.js'|head -1)
curl -s localhost:3000/assets/$JS | grep -oc 'pilots/health'   # expect 1
# user-ui MUST contain KPI tooltips:
JS=$(curl -s localhost:3001 | grep -oE 'index-[A-Za-z0-9_]+\.js'|head -1)
curl -s localhost:3001/assets/$JS | grep -oc 'Стоимость всех ваших'  # expect 1
```
Then in browser: admin sidebar shows «Когорты» + «Здоровье пилотов»; open /pilots
→ Test Org Score 100. If not → re-run the script + Ctrl+Shift+R.

---

## STEP 0b — demo seed fixes

**If DB was reset (`down -v`)** — run ALL FOUR once, in order:
```bash
cd docker
for f in 05-seed-volume-refresh 06-seed-tvl-rescale 07-seed-fix-backend-bugs 08-seed-balance-rescale; do
  docker exec -i dlmm-postgres psql -U dlmm -d dlmm < $f.sql
done
```

**⚠️ ALWAYS re-run 05 within a few hours of the demo (even without a DB reset):**
```bash
docker exec -i dlmm-postgres psql -U dlmm -d dlmm < docker/05-seed-volume-refresh.sql
```
WHY: pool-engine's `PoolScheduledTasks` recomputes `volume_24h` from the live
24-hour transaction window every few minutes. The seed swaps age out of that
window, so volume/APY drift back to 0 over time. Re-running 05 bumps the swap
timestamps into the last 24h → pools show real volume + APY (LKOH ~2.4%, SUSDT
~1.6%) and stay alive for ~24h.

**❗ Do NOT re-run 06 or 08** (TVL / balance rescale) — they divide by a
constant and are NOT idempotent; re-running shrinks values 10000× again.
07 is insert-if-missing (safe to re-run). 05 is safe to re-run.

Quick verify after 05:
```bash
T=$(curl -sX POST localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"ivanov@example.com","password":"Demo1234"}'|grep -oE '"accessToken":"[^"]+"'|cut -d'"' -f4)
curl -s 'localhost:8080/api/v1/pools?page=0&size=3' -H "Authorization: Bearer $T" \
  | grep -oE '"estimatedApy":[0-9.]+'   # expect non-zero
```

---

## Boot smoke

- [ ] `cd docker && docker-compose ps` — все 17 контейнеров `Up` (нет `Exited`, `Restarting`)
- [ ] `docker ps | grep healthy | wc -l` — ≥ 4 (postgres, redis, kafka, consistency-kafka все healthy)
- [ ] `curl -s http://localhost:8080/actuator/health | jq -r .status` → `UP`
- [ ] `curl -s http://localhost:8088/actuator/health | jq -r .status` → `UP`
- [ ] Все Spring services healthy:
  ```bash
  for s in dlmm-{user,token,pool,fee,transaction,notification,price-oracle}-service dlmm-gateway dlmm-admin-bff; do
    h=$(docker inspect $s --format '{{.State.Health.Status}}' 2>/dev/null || echo "n/a")
    echo "$s: $h"
  done
  # Expected: all "healthy" (or "n/a" if healthcheck not configured)
  ```

## Auth + JWT

- [ ] User login:
  ```bash
  curl -sX POST http://localhost:8080/api/v1/auth/login \
       -H 'Content-Type: application/json' \
       -d '{"email":"ivanov@example.com","password":"Demo1234"}' \
    | jq -r .accessToken | wc -c
  # Expected: > 100 chars
  ```
- [ ] Admin login same endpoint, `admin@sber-dlmm.ru` — same expectation
- [ ] R-04 refresh-token endpoint reachable:
  ```bash
  curl -sX POST http://localhost:8080/api/v1/auth/refresh \
       -H 'Content-Type: application/json' \
       -d '{"refreshToken":"x"}' -o /dev/null -w "%{http_code}\n"
  # Expected: 400 or 401 (refresh-token format invalid — endpoint exists)
  ```

## New batch #2 endpoints

- [ ] **R-01** restart policy:
  ```bash
  docker inspect dlmm-user-service --format '{{.HostConfig.RestartPolicy.Name}}'
  # Expected: unless-stopped
  ```
- [ ] **R-02** 2FA real secret (после user login):
  ```bash
  TOKEN=$(curl -sX POST http://localhost:8080/api/v1/auth/login \
       -H 'Content-Type: application/json' \
       -d '{"email":"ivanov@example.com","password":"Demo1234"}' | jq -r .accessToken)
  curl -sX POST http://localhost:8080/api/v1/users/me/2fa/begin \
       -H "Authorization: Bearer $TOKEN" | jq -r '.secret | length'
  # Expected: 32
  ```
- [ ] **S13-01** Resilience4j CB endpoints:
  ```bash
  curl -s http://localhost:8088/actuator/circuitbreakers | jq '.circuitBreakers | keys | length'
  # Expected: 6
  curl -s http://localhost:8084/actuator/circuitbreakers | jq '.circuitBreakers | keys | length'
  # Expected: 1 (token-service)
  ```
- [ ] **S13-02** audit log table has actor_type column:
  ```bash
  docker exec dlmm-postgres psql -U dlmm -d dlmm -c \
    "\d admin_audit_log" | grep actor_type
  # Expected: actor_type | character varying(10) | ...
  ```
- [ ] **S14-01** SAML metadata:
  ```bash
  curl -s http://localhost:8080/api/v1/auth/saml/metadata | head -3
  # Expected: <?xml version="1.0" encoding="UTF-8"?>\n<!-- ... \n<md:EntityDescriptor
  ```
- [ ] **S14-02** Cohort analytics endpoint:
  ```bash
  TOKEN_ADMIN=$(curl -sX POST http://localhost:8080/api/v1/auth/login \
       -H 'Content-Type: application/json' \
       -d '{"email":"admin@sber-dlmm.ru","password":"Demo1234"}' | jq -r .accessToken)
  curl -s "http://localhost:8080/api/v1/admin/cohorts?metric=DAU&days=7" \
       -H "Authorization: Bearer $TOKEN_ADMIN" | jq 'length'
  # Expected: > 0
  ```

## UI smoke

- [ ] Open `http://localhost:3001/#/login` — page renders (no infinite spinner, no blank screen)
- [ ] Login as ivanov → Dashboard loads in < 3s
- [ ] Click через все нижние tabs: Pools, Swap, Positions, Profile — каждая страница renders в < 2s
- [ ] **R-05** theme switch test: Profile → Settings → Theme: Light↔Dark — переключается мгновенно
- [ ] **S14-03** Reviews page: navigate /reviews — 4 testimonial cards visible
- [ ] **S14-03** Simple-mode: toggle ON → Ребаланс / Команда sidebar items hidden immediately
- [ ] **R-02** 2FA modal: Profile → Безопасность → "Включить 2FA" → modal shows 32-char secret + QR
- [ ] **S14-01** SSO button: /login → "Войти через корпоративный SSO" button visible

## Admin UI smoke

- [ ] Open `http://localhost:3000/#/login` → log in as admin
- [ ] Dashboard loads
- [ ] **S14-02** /admin/cohorts: LineChart renders, metric/period selectors work
- [ ] /admin/api-analytics: rate-limit panel loads

## Browser

- [ ] Console clean (0 errors / warnings) на каждой странице
- [ ] No 4xx / 5xx requests in Network tab (besides expected 501 for SSO endpoints)
- [ ] Browser cache cleared (Ctrl+Shift+R)
- [ ] Window sized to 1440×900 (typical demo screen)
- [ ] Bookmarks bar hidden (Ctrl+Shift+B)

## Seed data freshness

- [ ] Транзакции есть за последние дни (не all > 30 дней назад):
  ```bash
  docker exec dlmm-postgres psql -U dlmm -d dlmm -c \
    "SELECT MAX(created_at) FROM transactions"
  # Expected: within last 30 days
  ```
- [ ] LP positions есть для ivanov:
  ```bash
  docker exec dlmm-postgres psql -U dlmm -d dlmm -c \
    "SELECT COUNT(*) FROM positions WHERE user_id='a0000000-0000-0000-0000-000000000002'"
  # Expected: ≥ 5
  ```

## Emergency contacts

| Что | Куда |
|-----|------|
| Если демо упало | `docker-compose down && docker-compose up -d` (60s) |
| Если PG corrupted | Restore from latest `pg_dump` (see `docs/RUNBOOK.md`) |
| Если confused — restart everything | `docker-compose down -v && docker-compose up -d --build` (5–7 min) — крайняя мера, потеря всех seed данных |

---

*Полностью пройдите чеклист один раз перед каждым live demo. Не пропускайте даже "очевидные" пункты — surprise factor в живом демо стоит дороже 10 минут проверки.*
