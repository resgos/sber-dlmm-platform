# Stack walkthrough — Sprint 9 Day 3 demo readiness

**Build commit at the time of bring-up**: `27fd0ec` (OTC admin UI +
UX-A11Y-2 wave 2). Branch `claude/elated-elgamal-dba521`.

## 1. URLs

| Surface | URL | Notes |
|---|---|---|
| **Admin UI** | http://localhost:3000 | Sprint 9 OTC desk page is new |
| **User UI** | http://localhost:3001 | Sprint 9 i18n + a11y wave 2 |
| **Gateway** | http://localhost:8080 | API tiers active (FREE/PRO/ENTERPRISE) |
| **Public Data API** | http://localhost:8080/api/v1/public/pools/stats | No auth — Sprint 9 R-M-33 |
| **Grafana** | http://localhost:3030 | DLMM Overview dashboard |
| **Prometheus** | http://localhost:9090 | metrics + alert rules |
| **Swagger** | http://localhost:8083/swagger-ui.html | pool-engine; same on 8081-8088 for each service |
| **Status page** | http://localhost:3002 | if dlmm-status-page is running |

## 2. Credentials (seed data, dev only)

Password for every seed user: **`Demo1234`**

| Email | Role | KYC | Use for |
|---|---|---|---|
| `admin@sber-dlmm.ru` | ADMIN | VERIFIED | Admin UI — Dashboard, OTC, Users, Pools |
| `super-admin@sber-dlmm.ru` | SUPER_ADMIN | VERIFIED | `/admin/audit` endpoint + role-change tests |
| `ivanov@example.com` | USER | VERIFIED | User UI — Swap, Hedge, Pools, YSRUB mint/burn |
| `sidorov@example.com` | USER | VERIFIED | second test user (counterparty for transfers) |
| `suspect@example.com` | USER | PENDING | tests KYC-gated paths (blocked from swap) |

## 3. Sprint 9 demo flow (5 min run)

### A. New surface: OTC desk
1. Log in as `admin@sber-dlmm.ru` / `Demo1234` at http://localhost:3000
2. Sidebar → **OTC desk** (new menu item, ShopOutlined icon)
3. Click **Новая сделка**:
   - Initiator: `a0000000-0000-0000-0000-000000000002` (Ivanov)
   - Counterparty: `a0000000-0000-0000-0000-000000000003` (Sidorov)
   - Token IN: `b0000000-0000-0000-0000-000000000001` (SRUB)
   - Token OUT: `b0000000-0000-0000-0000-000000000002` (USDT — check tokens page for exact UUID)
   - Amount IN: 10000000 (10 млн SRUB)
   - Notes: "Sber Treasury → MOEX clearing, ref Q3-001"
4. Row appears with status **Запрошена** + amber action button **Котировать**
5. Click **Котировать** → enter amountOut=9500000, price=950000, expiry=+30min → submit
6. Status moves to **Котировка** (gold tag). New buttons: **Принять** / red close / cancel
7. Click **Принять** → status **Принята** (cyan) + Расчёт button appears
8. Click **Расчёт** → enter any settlement UUID → status **Расчёт выполнен** (green)
9. Status filter dropdown — try filtering to see only QUOTED or SETTLED

### B. YSRUB money market (backend live — call via curl/Swagger)
```bash
# Get JWT token first
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ivanov@example.com","password":"Demo1234"}' | jq -r .accessToken)

# Mint 100,000 YSRUB by depositing 100,000 SRUB
curl -X POST http://localhost:8080/api/v1/tokens/ysrub/mint \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"srubAmount": 100000, "idempotencyKey": "demo-mint-1"}'

# Check own movement history
curl http://localhost:8080/api/v1/tokens/ysrub/me \
  -H "Authorization: Bearer $TOKEN"

# Burn 50,000 YSRUB back to SRUB
curl -X POST http://localhost:8080/api/v1/tokens/ysrub/burn \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"ysrubAmount": 50000, "idempotencyKey": "demo-burn-1"}'
```

### C. API tiers + Public Data
```bash
# No-auth public stats (FREE-tier rate-limited by IP)
curl http://localhost:8080/api/v1/public/pools/stats

# Rate limit: hit FREE quickly to see 429 + X-Tier-Limit response header
for i in {1..30}; do
  curl -s -o /dev/null -w "%{http_code} " http://localhost:8080/api/v1/public/pools/stats
done
echo
# Expected: ~10-15 successes, then 429s
```

### D. Dashboard drill-down (M-4 + M-5)
1. http://localhost:3000/dashboard
2. Click **Верифицированные** tile → navigates to `/users?kycStatus=VERIFIED`
3. Click **Активные пулы** tile → `/pools?status=ACTIVE` — filter chip visible
4. Click **Общий TVL** tile → `/pools?sort=tvl` — table re-ordered
5. **Сбросить** button clears filters

### E. UX-A11Y wave 2 (keyboard-only demo)
1. Reload http://localhost:3000 — DO NOT click anywhere
2. Press **Tab** once → green **"Перейти к содержимому"** banner appears top-left
3. Press **Enter** → focus jumps to `<main>`, skipping the Sider tree
4. Continue Tabbing — Sider's collapse button announces "Развернуть/Свернуть меню"

### F. i18n surface (Sprint 8 C-4 + Sprint 9 C-4-rest)
1. http://localhost:3001 (user UI)
2. All strings now route through `t()` — `Обмен`, `Допуск проскальзывания`,
   tile titles, login form labels, hedge unwind copy
3. View page source on /pools — `pools.subtitle` plural correctly:
   "1 пара" / "2 пары" / "5 пар"

## 4. Health checks

```bash
# Per-service deep health (Spring Actuator)
for port in 8080 8081 8082 8083 8084 8085 8086 8087 8088; do
  echo -n "$port: "
  curl -s http://localhost:$port/actuator/health | jq -r '.status' 2>/dev/null || echo "DOWN"
done

# Expected: UP UP UP UP UP UP UP UP UP
```

```bash
# Grafana panels showing Sprint 9 work
# - "Yield distribution scheduler" (Sprint 9 #7.2) — fires daily 04:00 МСК
# - "API tier usage" (Sprint 9 #6.6) — per-tier requests/sec
# - "OTC desk transitions" (Sprint 9 #6.1) — state transitions/min
```

## 5. What was built (commit summary since session start)

| # | Commit | Feature |
|---|---|---|
| 1 | `8e63617` | C-4-rest: LoginPage + PoolsPage i18n extraction |
| 2 | `261c19e` | #6.6 + R-M-33: API tiers + Public Data API |
| 3 | `937e31e` | #7.1: YSRUB mint/burn (tokenized money market) |
| 4 | `40c3ea4` | #7.2: YSRUB daily yield distribution scheduler |
| 5 | `e2d2e93` | #M-4: dashboard tile drill-downs |
| 6 | `66022af` | #M-5: PoolsPage filter+sort via URL params |
| 7 | `75fd71e` | #6.1: OTC desk backend (entity + service + 18 tests) |
| 8 | `27fd0ec` | #6.1 + UX-A11Y-2: OTC admin UI + skip-to-content |

**8 commits this session, all on origin/claude/elated-elgamal-dba521.**

## 6. What's still pending in Sprint 9

| Item | Effort | Status |
|---|---|---|
| User-UI YSRUB mint/burn page | 2d | not started — Sprint 9 day 4 |
| 9.A PO trek: 1 OTC counterparty signed | — | non-code, depends on PO |
| C-10-bff: 2 of 5 remaining WebClients | 1d | not started |
| Spasibo write-back (CONDITIONAL on 8.C) | 8d | gated on contract |

Sprint 9 §5: **9 of 10 hard gates closed**. Last gate ("1 OTC ≥ 10M ₽
trade settled") requires the PO trek; backend + admin UI are ready.

---

*Recorded by IT-lead. Read-along for live walkthrough.*
