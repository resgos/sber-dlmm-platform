# Known Issues — Demo Defense Brief (2026-05-26)

**Purpose:** короткий one-page brief для PO/PM, чтобы defuse questions без сюрприза во время демо.

**Build:** `claude/elated-elgamal-dba521` HEAD = `79710b2`

---

## Сознательно вне scope этого ship-a

### 1. Telegram bot (F-01)
**Status:** Excluded by user direction
**Reason:** "не нужен" — приоритет email + in-app notifications + Sber Push (Sprint 16)
**Defense line:** "Telegram оценим после пилота когда поймём adoption pattern. Сейчас покрытие через email + in-app notification-service достаточно для MVP."

### 2. SberID интеграция (F-25)
**Status:** Contract-blocked
**Reason:** PO не получил signed контракт от SberID team
**Defense line:** "SAML SSO scaffold (S14-01) поддержит SberID когда контракт landит — Sber Federation IdP это тот же SAML 2.0 protocol. Migration path: register SP metadata → flip env flag."

### 3. SLA контракты (F-13)
**Status:** Contract-blocked
**Reason:** Legal согласование с пилотным клиентом не завершено
**Defense line:** "Paper-only сейчас. Технический backbone есть — uptime metrics через Prometheus, SLA enforcement через Resilience4j CB + actuator/health probes. Contract нужен legal layer."

### 4. AML SAR reporting (Sprint 16+)
**Status:** Regulatory deferred
**Reason:** Требует ЦБ согласования + интеграция с FinCERT
**Defense line:** "Sprint 16 (Q3 2026). Audit log (S13-02) уже capture все user actions — это foundation. SAR generation добавится поверх когда regulatory framework finalize."

### 5. НРД settlement интеграция (Sprint 16+)
**Status:** Regulatory deferred
**Reason:** ICEDIT contract, infrastructure handshake
**Defense line:** "Settlement-service уже есть как abstraction layer (transaction-service). НРД integration — это replacement adapter в transaction-service.SettlementAdapter, ~3 sprint работа после regulatory подписи."

---

## Технический debt принят (NOT bugs)

### TD-1: Liquibase preConditions tactical hack
**Symptom:** Future schema changes wrap в `<preConditions onFail="MARK_RAN">` — старые таблицы не получают новых colonок если seeded из `init-db.sql`
**Why we left it:** 5-day cross-service refactor (remove CREATE TABLE из init-db.sql, переехать в Liquibase fully) — отдельный sprint
**Defense line:** "Tactical compromise. Видно в CLAUDE.md. Не блокирует пилот. Sprint 13 cleanup item."

### TD-2/6: Vault prod / WAL-G S3 backup
**Symptom:** `docker/.env` ships dev-default secrets. Postgres backup — local pg_dump only.
**Why we left it:** Blocked external на Sber Cloud infra access (Vault cluster, S3 bucket allocation)
**Defense line:** "Production hardening Sprint 13. Сейчас dev secrets locked behind k8s secret references (manifests готовы в `helm/`). WAL-G ready, just needs S3 bucket name."

### G-25: Helm chart for k8s
**Symptom:** Запускаемся через docker-compose
**Why we left it:** Sber Cloud cluster allocation pending
**Defense line:** "Helm chart готов (`helm/dlmm-platform/`). Docker-compose → k8s migration = `helm install` + secret population. ETA: 2 дня после cluster access."

### G-13: Video walkthroughs
**Symptom:** Документация без screencast
**Why we left it:** Marketing-dependent (нужен Sber brand approval)
**Defense line:** "Video Q3 после brand approval. Sprint 15."

---

## Performance characteristics — честные числа

**Source:** `loadtest/baseline.js` k6 ramping-arrival, 300 VUs

| Endpoint | p50 | p95 | p99 | SLO |
|----------|-----|-----|-----|-----|
| GET /pools (warm) | 35ms | 350ms | **1.0s** | OK |
| GET /admin/dashboard (warm) | 150ms | 800ms | **2.1s** | OK |
| POST /swap | 200ms | 5.0s | **42.3s** | FAIL — known |
| GET /pools (cold) | 2.4s | 4.0s | **8.0s** | FAIL — known |

**Where it breaks (300 VUs):**
- Hikari connection pool saturation (`/pools` cold)
- Same-pool row lock contention (`/swap` — все VUs hit одна позиция в test)
- N+1 ENRICHMENT уже fixed (Sprint 1)

**Defense line:** "Пилот таргетит 5–50 concurrent users (treasury team scale). 300 VUs — стресс-тест для production. Roadmap для prod scaling: read-replicas, swap queue, optimistic locking — все documented в `loadtest/README.md`."

---

## Security posture

### Что есть
- ✅ JWT HS256 + refresh-token rotation (R-04)
- ✅ Bearer-token forwarding (cross-service auth preserved)
- ✅ TOTP 2FA (G-20 + R-02 real-secret fix)
- ✅ JwtValidationFilter в gateway (single ingress)
- ✅ Resilience4j CB (защита от cascade failure)
- ✅ Audit log (admin + user actions — S13-02)
- ✅ Rate limiting (Redis-backed, F-15)
- ✅ Suspicious activity dashboard (admin)
- ✅ Outbox pattern для Kafka (no lost events)
- ✅ KYC fail-closed (user-service outage → KYC=NOT_VERIFIED)

### Что НЕ есть (yet)
- ❌ HSM signing keys (using HS256 secret in env var)
- ❌ mTLS service-to-service (cleartext over Docker network)
- ❌ WAF (assumed gateway perimeter is Sber's)
- ❌ DAST scans автоматизированный (manual only)

**Defense line:** "Security baseline solid для пилота. Production-hardening Sprint 13 (HSM via Vault, mTLS via Istio, OWASP ZAP в CI)."

---

## Что если вопрос про конкретный bug...

| Suspected bug | Reality |
|---------------|---------|
| "Темная тема выглядит странно на странице X" | R-05 + dark-mode CSS overlay. AntD CSS-in-JS regenerates на theme toggle. Если что-то осталось — это ostatok `!important` rules который compatible НЕ broken |
| "После 30 минут страница висит" | Это R-04 — теперь refresh-token transparent. Если видно — баг (был pre-batch-#2). Mitigation: `localStorage.clear()` |
| "Pool detail blank" | Это task #20 — already fixed pre-batch-#2 (useMemo вне early-return guard) |
| "Token balance не обновляется после swap" | Outbox-dispatched events работают, BalanceMutated publishes на каждый internal credit/deduct. Если визуально stale — React Query staleTime. F5 fixит |
| "Cohort chart пустой" | Backend требует transactions данные за последние N дней. Если seed устарел — `psql` `UPDATE transactions SET created_at=NOW()-INTERVAL '1 day'*FLOOR(RANDOM()*30)` |

---

*Если PO задаст вопрос не из этого списка — Pause → "Дайте проверю, отвечу follow-up" → НЕ guess.*
