# Ultrareview — Batch #2 ship (2026-05-26)

**Branch:** `claude/elated-elgamal-dba521` HEAD = `79710b2`
**Base before batch:** `1a59fa7` (after roadmap doc commit)
**Delivered:** 10 PRs (#12 — #21) — week-1 residuals + Sprint 13 + Sprint 14 (без Telegram)
**Coordinator method:** 5 серийных волн × 2 параллельных агента (Variant C — partial rebuild на shared stack)

---

## 1. PR ship list

| PR | Unit | Title | Lines | Status |
|----|------|-------|-------|--------|
| #12 | R-01 | docker-compose `restart: unless-stopped` всех 9 Spring services | +19 | ✅ Merged |
| #13 | R-02 | 2FA modal — real secret instead of PENDING placeholder | +146/−8 | ✅ Merged |
| #14 | R-03 | /hedge pair cards dark mode + AntD overrides | +88/−9 | ✅ Merged |
| #15 | S13-02 | User-actions audit log (@UserAudit annotation + actor_type column) | +316/−23 | ✅ Merged |
| #16 | R-04 | JWT transparent refresh-token on 401/403 (no more 30-min spinners) | +438/−22 | ✅ Merged |
| #17 | S13-01 | Resilience4j CB+retry for admin-bff (6 downstreams) + fee-service | +654/−221 | ✅ Merged |
| #18 | R-05 | ConfigProvider reactive to themeStore (live theme switch w/o reload) | +109/−25 | ✅ Merged |
| #19 | S14-02 | Cohort analytics admin dashboard (DAU/MAU/D7/D30 + recharts) | +564/−25 | ✅ Merged |
| #20 | S14-03 | Reviews page (/reviews) + Simple-mode sidebar toggle | +1126/−64 | ✅ Merged |
| #21 | S14-01 | SAML 2.0 SSO scaffolding (Azure AD / Sber Federation) | +934/−28 | ✅ Merged |

**Totals:** ~4400 LOC added, 100% merged, 0 conflicts requiring manual resolution (S14-* branches chained off R-05 base, S13-01 had auto-merge only on application.yml).

---

## 2. Excluded / parked

Per user explicit direction:
- **F-01 Telegram bot** — "не нужен"

Per plan, parked until next batch:
- **TD-1** Liquibase preConditions cleanup (5d cross-service refactor)
- **TD-2** Vault prod / **TD-6** WAL-G S3 / **G-25** Helm cluster — blocked on Sber infra external
- **G-13** Video — marketing-dependent
- **F-13** SLA contracts / **F-25** SberID — PO contract-blocked
- **Sprint 16+** items — regulatory/legal

---

## 3. Backend smoke validation

Acceptance criteria per unit (to be filled during sweep):

### R-01: restart policy
```bash
for s in dlmm-{user,token,pool,fee,transaction,notification,price-oracle}-service dlmm-gateway dlmm-admin-bff; do
  echo "$s: $(docker inspect $s --format '{{.HostConfig.RestartPolicy.Name}}')"
done
# Expected: all 9 services show "unless-stopped"
```

### R-02: 2FA modal real secret
```bash
# After login as ivanov@example.com:
curl -sX POST "http://localhost:8080/api/v1/users/me/2fa/begin" \
     -H "Authorization: Bearer $TOKEN" | jq -r '.secret | length'
# Expected: 32
```

### R-04: JWT refresh
```bash
# Manual: login → wait 30+ min → trigger any API call → no /login redirect
# Check browser DevTools: see /auth/refresh request fire, then original retried with new token
```

### S13-01: Resilience4j
```bash
curl -s http://localhost:8088/actuator/circuitbreakers | jq '.circuitBreakers | keys'
# Expected: [ "fee-service", "pool-engine", "price-oracle", "token-service", "transaction-service", "user-service" ]
```

### S13-02: User-actions audit log
```bash
# After a swap as ivanov:
docker exec dlmm-postgres psql -U dlmm -d dlmm -c \
  "SELECT action_type, actor_type, user_email, timestamp FROM admin_audit_log \
   WHERE actor_type='USER' ORDER BY timestamp DESC LIMIT 5"
# Expected: actor_type=USER rows for SWAP / CLAIM_FEES / SETTLEMENT
```

### S14-01: SAML SSO scaffold
```bash
curl -s http://localhost:8080/api/v1/auth/saml/metadata | head -20
# Expected: <md:EntityDescriptor ... entityID="https://dlmm.sber-online.ru/saml/sp" ...>
curl -s http://localhost:8080/api/v1/auth/saml/initiate | jq
# Expected: { "status": "SAML_SSO_NOT_CONFIGURED", "message": "...", "nextStep": "..." } (HTTP 501)
```

### S14-02: Cohort analytics
```bash
TOKEN_ADMIN=$(curl -sX POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@sber-dlmm.ru","password":"Demo1234"}' | jq -r .accessToken)
curl -s "http://localhost:8080/api/v1/admin/cohorts?metric=DAU&days=30" \
     -H "Authorization: Bearer $TOKEN_ADMIN" | jq '.[0]'
# Expected: { "date": "2026-05-...", "value": <int> }
```

---

## 4. UI sweep — 23 pages

Methodology: take screenshot of each page in BOTH light and dark mode, verify no broken layouts, no console errors, dark-mode contrast passes.

### User UI (14 pages)

| # | Path | Status | Notes |
|---|------|--------|-------|
| 1 | /login | TBD | R-05 reactive theme test — toggle should flip live |
| 2 | / (dashboard) | TBD | hero, KPIs, СберСпасибо, "Мои токены" table |
| 3 | /pools | TBD | PoolCards + pagination + Сравнить |
| 4 | /pools/<id> | TBD | header, KPI tiles, OHLCV chart, bin distribution |
| 5 | /pools/<id>/liquidity | TBD | G-22 preview panel WORKING (TVL%, warnings, in-range chip) |
| 6 | /pools/compare | TBD | side-by-side 2 pools |
| 7 | /swap | TBD | main form, popular pairs, top pools |
| 8 | /hedge | TBD | R-03 fix — pair-selector cards DARK in dark mode |
| 9 | /positions | TBD | KPI, filters, "Забрать всё" mass-action |
| 10 | /rebalance | TBD | 3-step Steps + portfolio table |
| 11 | /team | TBD | "Test Org" + 2 members |
| 12 | /transactions | TBD | 8 rows + filters |
| 13 | /profile → 3 tabs | TBD | R-02 2FA modal real secret + S14-03 simple-mode toggle |
| 14 | /reviews ⭐ NEW | TBD | S14-03 — 4 testimonial cards |

### Admin UI (9 pages)

| # | Path | Status | Notes |
|---|------|--------|-------|
| 15 | /admin/login | TBD | |
| 16 | /admin (dashboard) | TBD | TVL hero + KPIs + ops + top pools |
| 17 | /admin/cohorts ⭐ NEW | TBD | S14-02 — DAU/MAU/D7/D30 LineChart |
| 18 | /admin/api-analytics | TBD | rate-limit dashboard |
| 19 | /admin/transactions | TBD | basic regression |
| 20 | /admin/users | TBD | basic regression |
| 21 | /admin/tokens | TBD | basic regression |
| 22 | /admin/pools | TBD | basic regression |
| 23 | /admin/suspicious | TBD | basic regression |

---

## 5. Cross-cutting validations

- [ ] **Live theme switch (R-05)** — toggle Profile → Settings → Theme → ALL AntD components flip without page reload, no flash
- [ ] **JWT refresh (R-04)** — leave tab idle > 30 min → first API call silently refreshes, no /login redirect
- [ ] **Simple-mode (S14-03)** — toggle → Ребаланс, Команда disappear from sidebar immediately; reload preserves preference
- [ ] **Dark mode regression** — every page renders correctly in dark mode (R-05 dark algorithm + sber-theme.css overlay)
- [ ] **Console clean** — 0 errors / warnings during full walkthrough
- [ ] **Healthchecks** — all 9 Spring services UP within 60s of `docker-compose up -d`

---

## 6. Demo-readiness summary (to be completed)

To be filled after sweep — acceptance per task #25 demo-ready criteria:
- ✅ / ❌ 0 console errors on any page
- ✅ / ❌ 0 broken pages / infinite spinners
- ✅ / ❌ Theme switch smooth (R-05)
- ✅ / ❌ 2FA setup modal shows real 32-char secret (R-02)
- ✅ / ❌ Multi-user, auto-claim, price-impact, cohort analytics visually proven
- ✅ / ❌ JWT refresh seamless after 30-min idle (R-04)
- ✅ / ❌ Tag `v0.12.0-demo-ready` cut from `claude/elated-elgamal-dba521` HEAD

---

*Sweep in progress. This document will be updated as each section completes.*
