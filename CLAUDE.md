# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Sber DLMM (Dynamic Liquidity Market Maker) — a concentrated-liquidity exchange (price "bins" + volatility-driven variable fee). Backend is a **Maven multi-module Spring Boot 3.2.5 / Java 21** build: 10 modules = 9 services (gateway + 8 domain/BFF services) plus the `dlmm-common` library. Two **React 18 + Vite 5 + Ant Design 5** SPAs (admin + user). All client traffic enters through `dlmm-gateway` (Spring Cloud Gateway). Persistence is PostgreSQL 16 + Liquibase, eventing is Kafka, Redis holds cache / rate-limit / JWT-revocation state, ClickHouse holds analytics. The base quote currency is **SRUB** (with **YSRUB**, a yield-bearing variant).

## Common commands

Run from the repo root (your active worktree) unless noted.

**Backend (Maven multi-module):**
```bash
mvn -DskipTests package                    # build all modules
mvn -pl dlmm-pool-engine -am package       # build one module + its deps
mvn -pl dlmm-pool-engine spring-boot:run   # run one service (needs Postgres/Redis/Kafka up)
mvn -pl dlmm-pool-engine test                                          # module tests
mvn -pl dlmm-pool-engine test -Dtest=SwapServiceTest                  # one class
mvn -pl dlmm-pool-engine test -Dtest=SwapServiceTest#shouldExecuteSwap  # one method
```
Running a service locally needs the secrets in the env, e.g.
`DB_PASSWORD=... JWT_SECRET=<≥32 bytes> mvn -pl dlmm-pool-engine spring-boot:run`
(`SecretValidationOnStartup` refuses to boot on a missing/short secret). JaCoCo is configured in the root `pom.xml` (prepare-agent + report bound to the `test` phase) → `target/site/jacoco/index.html` per module; no coverage threshold is enforced yet. Tests concentrate in `dlmm-common` (security/outbox/math) and `dlmm-pool-engine` (swap/liquidity, `FullSwapFlowIT` via Testcontainers).

**Frontends (`dlmm-admin-ui`, `dlmm-user-ui`):**
```bash
npm install
npm run dev          # admin :3000, user :3001 — both proxy /api → gateway :8080
npm run build        # tsc && vite build — TS errors fail the build
npm run test         # vitest run
npm run test:e2e     # Playwright (user-ui only)
npm run lint:all     # custom ratchets — see below
```
There is **no ESLint**. Style discipline is enforced by `scripts/check-no-hex-in-tsx.mjs` (no hardcoded hex in `.tsx`) and `scripts/check-inline-styles.mjs`, both diffed against `scripts/*-baseline.json`; re-baseline with `node scripts/check-no-hex-in-tsx.mjs --update`. `scripts/check-ui-shared-drift.mjs` guards code shared between the two UIs. Admin UI runs backend-less with `VITE_USE_MOCKS=true npm run dev` (axios-mock-adapter, `src/api/mockApi.ts`).

**Full stack (Docker Compose, ~19 containers):**
```bash
cp docker/.env.example docker/.env         # secrets (gitignored): DB_PASSWORD, JWT_SECRET, ...
cd docker && docker compose up -d
docker compose logs -f dlmm-gateway        # wait for "Started DlmmGatewayApplication"
docker compose down -v                     # full reset (drops the Postgres volume)
```
Compose builds each Java service from the root `Dockerfile` with `--build-arg MODULE=dlmm-xxx`; UIs use `Dockerfile.frontend` / `Dockerfile.user-frontend`. It brings up infra + 9 services + 2 UIs + an nginx status-page + Prometheus/Grafana. Demo creds: user `ivanov@example.com` / `Demo1234`, admin `admin@sber-dlmm.ru` / `Demo1234`.

**Deploy frontend changes to a running stack** (this is *not* a rebuild):
```bash
bash scripts/redeploy-frontends.sh   # host `npm run build` + docker cp dist into the nginx containers
```
Re-run this after **every** `docker compose up` — the copied `dist` is lost when a container is recreated — then hard-refresh the browser. `nginx.conf` changes are NOT covered by the script; `docker cp` them in and `nginx -s reload` manually.

## Architecture (the parts you can't infer from one file)

This repo is normally worked from a git **worktree** under `.claude/worktrees/` (there are many). Run commands from your active worktree, not the main clone.

### Service map and ports
Every service exposes `/actuator/**` and Swagger UI at `/swagger-ui.html`.

| Module | Port | Role |
|---|---|---|
| `dlmm-gateway` | 8080 | Spring Cloud Gateway (reactive). Sole public entry; JWT validation, Redis rate-limit, route table + auth-skip list in `application.yml`; injects `X-User-Id`/`X-User-Role`/`X-Kyc-Status` upstream |
| `dlmm-user-service` | 8081 | Auth (login/register/refresh/logout), users, KYC. **Issues** the JWTs |
| `dlmm-token-service` | 8082 | Token catalog + balances; internal deduct/credit; YSRUB money-market; custody fees |
| `dlmm-pool-engine` | 8083 | Core DLMM: bins, liquidity, swaps, variable fee; scheduled liquidity ops; margin watch |
| `dlmm-fee-service` | 8084 | Fee calc + distribution |
| `dlmm-transaction-service` | 8085 | Transaction ledger, settlement, AML scan scheduler |
| `dlmm-price-oracle` | 8086 | Price feeds + OHLCV (`/api/v1/oracle/ohlcv/{poolId}`) |
| `dlmm-notification-service` | 8087 | Kafka consumer → notifications; Kafka consumer-lag healthcheck |
| `dlmm-admin-bff` | 8088 | Backend-for-frontend aggregating admin endpoints |
| `dlmm-common` | (lib) | Shared cross-cutting infra (below) + DTOs/enums/`BinMath`/`FeeCalculator`/`GlobalExceptionHandler` |
| `dlmm-admin-ui` / `dlmm-user-ui` | 3000 / 3001 | React + AntD SPAs |

### `dlmm-common` holds the shared infra — auto-configured, don't re-implement per service
Each concern is a Spring `@AutoConfiguration` registered in `META-INF/spring/...AutoConfiguration.imports`, so every module that depends on `dlmm-common` gets it automatically:
- **JWT** — `JwtTokenProvider` + `JwtAuthenticationFilter` in `com.sber.dlmm.common.security`, wired by `DlmmJwtAutoConfiguration`. This replaced ~7 per-service copies; the only other JWT code is the *issuer* `JwtTokenProvider` in user-service and the reactive `JwtValidationFilter` in the gateway. The filter **rejects refresh tokens on business endpoints**. Claims carry `role`, `kycStatus`, `tier` (FREE/PRO/ENTERPRISE → API-tier rate limits) and `jti` (Redis revocation denylist).
- **Transactional outbox** — `com.sber.dlmm.common.outbox` (`OutboxService`, `OutboxDispatcher`, `DlmmOutboxAutoConfiguration`). Domain code calls `outbox.append(...)` *inside* the business transaction (propagation MANDATORY) instead of `kafkaTemplate.send` directly; a scheduled dispatcher drains rows to Kafka. `outbox_events` has a `service` column so each service drains only its own rows. Used by token-service and pool-engine (Swap/Liquidity/Pool).
- **Bearer forwarding** — `BearerTokenForwardingFilter` + `DlmmWebClientAutoConfiguration` copy the inbound `Authorization` header onto outbound `WebClient` calls (without it, inter-service calls 403).
- **Errors** — throw `DlmmException` (with an error code); `GlobalExceptionHandler` renders the HTTP body. Don't hand-build error `ResponseEntity`s.
- **Startup secret validation** — `SecretValidationOnStartup` aborts boot if `dlmm.jwt.secret` is missing or < 32 bytes.

### Auth flow
1. Client → `POST /api/v1/auth/login` (gateway skips auth for `/auth/{login,register,refresh}` and `/actuator/**`).
2. user-service returns an access token (HS384, ~30 min) + refresh token (7 days).
3. Gateway validates every other request, decodes the claims, and injects the `X-User-*` headers upstream.
4. Each downstream re-validates via the shared `JwtAuthenticationFilter` and builds the Spring `Authentication`.

### Inter-service resilience
Inter-service clients use **Resilience4j** (`@CircuitBreaker`/`@Retry`/`@TimeLimiter`) — present in pool-engine, fee-service, transaction-service and admin-bff clients. pool-engine's KYC lookup (`UserServiceClient`) is **fail-closed**: on a user-service outage the user is treated as *not* verified.

### Data + migrations
Single Postgres DB `dlmm`. Schema is bootstrapped by `docker/init-db.sql`; Liquibase runs `validate-on-migrate` and JPA `ddl-auto: validate`, so **a schema change needs a Liquibase changeset, not just an entity edit**. Per-service changesets wrap `createTable` in `<preConditions onFail="MARK_RAN">` so they coexist with `init-db.sql` (a known tactical hack — see `docs/DB-MIGRATION-CONVENTION.md`). Demo data is layered numbered seed scripts `docker/02..09-*.sql`, applied by the Postgres entrypoint.

### Kafka
Topics in `docker/init-kafka-topics.sh`: `user-events`, `token-events`, `pool-events`, `fee-events` (3 partitions, RF=1). Producers publish via the outbox; notification-service consumes.

### DLMM core + the bin invariant
Liquidity lives in price **bins**; each bin must hold `reserveX·price + reserveY = liquidity`. `SwapService` conserves this correctly. ✅ **F-12 (was an open correctness bug; fixed Sprint 10):** some *seeded* pools violated the invariant — the 210 history swaps were SQL inserts that set bin reserves without maintaining `liquidity` — so an isolated add→remove (no swaps between) over-returns the quote token (~49%, SBTC worst). Pool conservation still holds (not money-from-nothing) but it's an unfair split at other LPs' expense. **Fixed:** `docker/10-seed-reconcile-bin-invariant.sql` (run LAST in PRE-DEMO STEP 0b) restores `liquidity = reserve_x·price + reserve_y` for every bin and proportionally scales `position_bins.shares` so no bin is over-owned — idempotent via `seed_markers`, self-checks both post-conditions. Canonical formula + unit test: `BinMath.binLiquidity` + `BinMathFeeGrowthTest`. On a DB seeded BEFORE this script ran, still avoid live add+remove until it's applied. Full write-up: `docs/SESSION-HANDOFF-2026-05-29.md §5`.

## Frontend conventions
- **API:** one Axios instance per UI in `src/api/client.ts`, `baseURL: '/api/v1'`; a request interceptor adds `Bearer` from the Zustand `authStore`; 401 → redirect to `/login`.
- **State:** only auth is in Zustand; all server state is TanStack Query. No Redux.
- **Routing:** React Router 6 with `ProtectedRoute` / `ProtectedLayout`.
- **UI:** Ant Design 5 + Pro Components used directly (no design-system layer). Theming is via CSS variables in `src/sber-theme.css` (spacing `--space-*`, data-viz palette, dark variants) — **no hardcoded hex in `.tsx`** (lint ratchet). user-ui adds i18n (i18next) and a canvas price chart via `lightweight-charts`; other charts use Recharts.
- TS `noUnusedLocals` / `noUnusedParams` are intentionally disabled.

## Operational gotchas (these have cost real hours — also see the latest SESSION-HANDOFF + `docs/PRE-DEMO-CHECKLIST.md`)
- **Backend `docker compose build` is flaky** — it can exit 0 without actually updating the image (BuildKit tag race). Verify with `docker images <svc> --format '{{.CreatedAt}}'`; use `--no-cache` when in doubt.
- **Seed-script idempotency:** `05-seed-volume-refresh.sql` is safe and **must** re-run within ~24h of a demo (the pool-engine scheduler ages swaps out of the 24h window, drifting volume/APY to 0). `06-seed-tvl-rescale.sql` and `08-seed-balance-rescale.sql` are **NON-idempotent — never re-run** (they divide by a constant). `07` and `09` are safe/idempotent. Order after `down -v`: 05, 06, 07, 08, 09.
- **Recharts pages time out CDP `captureScreenshot`** (continuous ResizeObserver repaint) — not a real freeze; verify with page text. lightweight-charts (canvas) screenshots fine.

## Where the planning + ops knowledge lives
`docs/` is large and authoritative. Start with the newest **`SESSION-HANDOFF-*.md`** (current operational truth + hard-won gotchas), then `PRE-DEMO-CHECKLIST.md`, `SPRINT-PLAN.md`, the per-sprint `SPRINT-N-*.md`, `RISK-REGISTER.md`, the runbooks in `docs/runbooks/`, and `IMPROVEMENTS-REPORT.md`. A Helm chart is in `helm/dlmm-platform/`; CI is in `.github/workflows/` (backend, frontend, container-scan, schema-drift, runbook-drift, deploy).

## Team norms
The user communicates in **Russian** — mirror it in replies. Prefers **Opus**. High autonomy: proceed and do the work rather than asking for confirmation on routine steps.
