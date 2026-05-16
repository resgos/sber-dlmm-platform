# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Sber DLMM (Dynamic Liquidity Market Maker) — Spring Boot 3.2.5 / Java 21 microservices backend (Maven multi-module) plus two React 18 + Vite + Ant Design frontends (admin + user). All inter-service traffic flows through `dlmm-gateway` (Spring Cloud Gateway). Persistence is PostgreSQL 16 + Liquibase, eventing is Kafka, cache and rate-limiting state live in Redis, analytics in ClickHouse.

## Common commands

Run from repo root unless noted.

**Backend (Maven multi-module, parent `pom.xml`):**
```bash
mvn -DskipTests package                       # build everything
mvn -pl dlmm-pool-engine -am package          # build one module + its deps
mvn -pl dlmm-pool-engine spring-boot:run      # run one service locally (needs Postgres/Redis/Kafka up)
mvn -pl dlmm-pool-engine test                 # run module tests
mvn -pl dlmm-pool-engine test -Dtest=SwapServiceTest                       # single test class
mvn -pl dlmm-pool-engine test -Dtest=SwapServiceTest#shouldExecuteSwap     # single test method
```
Note: there is **no surefire/JaCoCo plugin configured** — `mvn test` only picks up tests via Spring Boot's default. Test coverage is currently very thin (only `dlmm-common` and `dlmm-pool-engine` ship tests).

**Full stack (Docker Compose):**
```bash
cd docker && docker-compose up -d             # brings up infra + all 10 services + 2 UIs
docker-compose logs -f dlmm-pool-engine       # tail one service
docker-compose down -v                        # full reset (drops volumes / Postgres data)
```
Compose builds each Java service via the root `Dockerfile` with `--build-arg MODULE=dlmm-xxx` (multi-stage Maven → JRE). Frontends use `Dockerfile.frontend` / `Dockerfile.user-frontend`.

**Frontends (standalone dev):**
```bash
cd dlmm-admin-ui && npm install && npm run dev   # http://localhost:3000
cd dlmm-user-ui  && npm install && npm run dev   # http://localhost:3001
npm run build                                    # tsc + vite build (TS errors fail the build)
```
Both Vite dev servers proxy `/api` → `http://localhost:8080` (the gateway). The admin UI also has `src/api/mockApi.ts` (axios-mock-adapter) which can run the UI without a backend — see `setupMockApi()`.

## Architecture (the parts that aren't obvious from a single file)

### Service map and ports
| Service | Port | Role |
|---|---|---|
| `dlmm-gateway` | 8080 | Spring Cloud Gateway. Single public entry point. JWT validation, rate limiting (Redis), route table in `dlmm-gateway/src/main/resources/application.yml` |
| `dlmm-user-service` | 8081 | Auth (login/register/refresh), users, KYC |
| `dlmm-token-service` | 8082 | Tokens + user balances |
| `dlmm-pool-engine` | 8083 | Core DLMM math: bins, liquidity, swaps. Has scheduling for liquidity ops |
| `dlmm-fee-service` | 8084 | Fee calc and distribution |
| `dlmm-transaction-service` | 8085 | Transaction recording, settlement |
| `dlmm-price-oracle` | 8086 | Price feed aggregation |
| `dlmm-notification-service` | 8087 | Kafka-driven notifications |
| `dlmm-admin-bff` | 8088 | BFF aggregating admin endpoints |
| `dlmm-common` | (lib) | Shared DTOs, enums, exceptions, `BinMath`, `FeeCalculator`, `GlobalExceptionHandler` |
| `dlmm-admin-ui` | 3000 | React/AntD admin |
| `dlmm-user-ui` | 3001 | React/AntD user-facing |

### Auth flow — important
1. Client posts to `/api/v1/auth/login` (gateway skips auth for `/auth/{login,register,refresh}` and `/actuator/**`).
2. `dlmm-user-service` issues a JWT (HS256, secret in `dlmm.jwt.secret`).
3. Gateway's `JwtValidationFilter` validates every other request, decodes claims, and **injects `X-User-Id`, `X-User-Role`, `X-Kyc-Status` headers** into the upstream request.
4. Each downstream service has its own `JwtAuthenticationFilter` + `JwtTokenProvider` that re-validates the token and builds `Authentication`. **Yes, this is duplicated** across 7 services (~939 lines total) — see "Known debt" below.

### Kafka topics
Defined in `docker/init-kafka-topics.sh`: `user-events`, `token-events`, `pool-events`, `fee-events`. Each is 3 partitions, RF=1. `dlmm-notification-service` consumes; producers live in the corresponding domain services.

### Database
Single Postgres database `dlmm`, user `dlmm`, password `dlmm_secret` (dev default). Schema is bootstrapped by `docker/init-db.sql` (~470 lines) — Liquibase is wired with `validate-on-migrate`, **not auto-update**, so schema changes require a Liquibase changeset, not just an entity edit. JPA `ddl-auto: validate` enforces this.

### Frontend conventions
- API client: `src/api/client.ts` in both UIs — single Axios instance, `baseURL: '/api/v1'`, request interceptor adds `Bearer` from `authStore` (Zustand), response interceptor force-redirects to `/login` on 401.
- State: only auth is in Zustand. Server state is React Query (`@tanstack/react-query`). Don't add Redux.
- Routing: React Router 6 with `ProtectedRoute` / `ProtectedLayout` wrappers (see `src/routes` or `src/components`).
- UI kit: Ant Design 5 + Pro Components. No design system layer — pages call AntD directly.
- TS strictness: `noUnusedLocals`/`noUnusedParams` are intentionally disabled in `tsconfig.json` (commit `700a185`).

## Conventions worth knowing

- **Java 21** with Lombok and MapStruct annotation processors (configured in root `pom.xml`). `mvn idea:idea` / IDE annotation-processing must be enabled.
- **All HTTP exceptions** go through `dlmm-common`'s `GlobalExceptionHandler` — throw `DlmmException` (with error code) rather than building `ResponseEntity` manually.
- **No service-to-service circuit breaker** is configured (no Resilience4j). Inter-service calls are bare `RestTemplate`/`WebClient`.
- **Default JWT secret** in committed `application.yml` files is `change-me-in-production-...` — always overridden via `JWT_SECRET` env in Compose. Don't commit a real secret.
- **Actuator** is enabled per service: `/actuator/health,info,metrics,prometheus`.
- **Swagger** is per service: `http://localhost:<port>/swagger-ui.html`, OpenAPI JSON at `/v3/api-docs`.

## Recent fixes (post-feaecfa baseline)

The session under `claude/elated-elgamal-dba521` closed several blockers and
the major shared-infra refactors below. Read this before assuming the codebase
matches the original feaecfa state:

- **JWT consolidated in `dlmm-common`** — `JwtTokenProvider` + `JwtAuthenticationFilter`
  are now in `com.sber.dlmm.common.security` and auto-registered via
  `DlmmJwtAutoConfiguration`. Per-service copies (7 of each) were deleted.
  Note: the shared filter **rejects refresh tokens on business endpoints**
  (only valid against `/auth/refresh`) — this is stricter than some old
  per-service filters were.
- **Bearer-token forwarding on outbound WebClient calls** — `BearerTokenForwardingFilter`
  + `DlmmWebClientAutoConfiguration` install a `WebClientCustomizer` that copies
  the inbound `Authorization` header onto outgoing `WebClient` requests. Without
  it, `pool-engine -> token-service` and `admin-bff -> downstream` calls returned
  403. Lives in a dedicated auto-config so user-service (no spring-webflux) doesn't
  trip on `WebClient$Builder` introspection.
- **`UserServiceClient` split out of `TokenServiceClient`** — `isUserKycVerified`
  now points at `dlmm-user-service` (it always was supposed to). Requires
  `USER_SERVICE_URL` env (set in compose).
- **Swap pipeline now functional end-to-end.** Two missing pieces were added:
  `GET /api/v1/users/internal/{id}/kyc` in user-service and
  `POST /api/v1/tokens/internal/{deduct,credit}` in token-service. Verified
  against the live stack (real swap, balance mutations match).
- **Resilience4j** on pool-engine `TokenServiceClient` and `UserServiceClient`
  with circuit-breaker + retry + timeout. KYC fallback is **fail-closed**
  (treats user as not verified on user-service outage).
- **Liquibase preConditions** — every per-service changeset wraps `createTable`
  in `<preConditions onFail="MARK_RAN"><not><tableExists/></not></preConditions>`
  so changesets coexist with `init-db.sql`. Tactical — see backlog for the
  proper split.
- **TokenType enum extended** with `FIAT_BACKED`, `COMMODITY_BACKED`, `UTILITY`,
  `INDEX_TOKEN` to match seed data. Pinned by `TokenTypeTest` so future edits
  are deliberate.
- **Test coverage grew** — 11 new cases for `JwtTokenProvider`, 5 for
  `JwtAuthenticationFilter`, 4 for `BearerTokenForwardingFilter`, 3 for
  `TokenType` (96 unit tests across `dlmm-common` + `dlmm-pool-engine` now
  green; `FullSwapFlowIT` Testcontainers swap E2E updated to mock `UserServiceClient`).
- **Secrets out of YAML** — `${DB_PASSWORD:?required}` / `${JWT_SECRET:?required}`,
  values come from `docker/.env` (gitignored), template in `docker/.env.example`.
- **Extended catalog seed** — `docker/02-extended-assets.sql` adds 18 tokens
  (Russian blue-chips, MOEX index tokens, FX-pegged, additional commodities)
  and 18 pools paired against SRUB. Loaded by postgres entrypoint after
  `init-db.sql`.

## Sprint 1 deliverables (2026-05-16, committed in this branch)

- **Pool-engine N+1 fix** — `GET /api/v1/tokens/batch?ids=csv` batch endpoint
  on token-service, Caffeine LRU cache on `TokenServiceClient`
  (60s TTL, 500 entries). `/admin/dashboard` cold 2.9s → warm 150–330ms,
  `/pools` cold 2.4s → warm 35–106ms. See commit `f4fca78`.
- **Deep healthchecks** across all services — `EndpointRequest.toAnyEndpoint()`
  in every SecurityConfig (fixes Spring 6.2 MvcRequestMatcher silent-skip),
  `spring-boot-starter-actuator` added to the 4 services that lacked it,
  `show-details: always` + `probes.enabled` enabled per service.
  `DownstreamHealthIndicator` in pool-engine (pings token-service +
  user-service) and admin-bff (pings 5 downstreams). Kill-and-restore
  drill verified — downstream DOWN reflects within 1s, recovery within 3s.
  See commit `c3fb54c`.
- **Seed enrichment** — `docker/03-seed-trading-history.sql` adds 210 swap
  transactions across 30 days, 22 pools, 3 users, with realistic status
  mix + 8 extra LP positions + matching position_bins + fee_accruals.
  Updates pool rollups (`volume_24h`, `total_fees_collected_x/y`) to match.
  Fixed two dashboard bugs along the way: `transactionsToday` was the page
  size (now date-filtered), `activePositions` was hardcoded 0 (now hits
  new `GET /api/v1/pools/positions/count`). See commit `2f800e7`.
- **Transactional outbox in token-service** — `outbox_events` table
  (Liquibase changeset 003 + init-db.sql), `OutboxEvent` + `OutboxService`
  (propagation=MANDATORY so dual-writes fail loudly) + `OutboxDispatcher`
  (@Scheduled 500ms, batch=100, retry on failure). All 4 `kafkaTemplate.send`
  sites rewired to `outbox.append`. Critically, the two internal endpoints
  (`deductInternal` / `creditInternal`) now publish `BalanceMutated` events —
  previously they were Kafka-silent so every swap was invisible to
  notification-service. Kill-Kafka drill verified — swaps stay sub-second,
  events buffer in outbox, dispatcher drains automatically on recovery.
  See commit `d03742a`.
- **Docs** — `docs/GLOSSARY.md` (1-pager domain vocab),
  `docs/RISK-REGISTER.md` (18 risks scored S×L), `docs/DEMO-SCRIPT.md`
  (20-min demo flow + Q&A bank for PO/IT-lead/sysAnalyst/demo-day
  questions). See commit `8e00036`.

## Known debt (still open)
- **Pool-engine outbox** not yet wired — pool state mutations
  (active_bin updates, fee accruals on swap) follow the same pattern
  but are Sprint 2 work.
- **Liquibase preConditions are a tactical hack.** Future schema changes won't
  apply on existing DBs (the changeset will be MARK_RAN'd because the table
  already exists). The proper fix is to remove `CREATE TABLE` from `init-db.sql`
  and move seed inserts into a Spring `@PostConstruct` runner or a Liquibase
  `<sqlFile>` step.
- **Resilience4j** not yet wired in `dlmm-fee-service` and `dlmm-admin-bff`
  (deps added, annotations not). pool-engine is fully covered.
- **TokenType extended values** (`FIAT_BACKED` etc.) are not handled in any
  business logic `switch`. They behave as opaque labels for the catalog UX.
- `docker/.env` ships dev-default secrets. Production should override via
  `Vault`/`AWS Secrets Manager`, not this file.
- `dlmm-pool-engine` returns mojibake-encoded cyrillic in JSON (double UTF-8
  encoding); affects all backend responses.
- No CI/CD pipeline, no Helm chart, no DB backup story. See IMPROVEMENTS-REPORT.md
  for the full systems-analyst review.
