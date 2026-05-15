# Sber DLMM Platform

Биржа ликвидности на основе **Dynamic Liquidity Market Maker** (бины концентрированной ликвидности и переменная комиссия от волатильности). Бэкенд — 10 микросервисов на Spring Boot 3.2 / Java 21, фронтенд — две панели на React 18 + Ant Design (админка и пользовательская).

```
                        ┌──────────────┐  ┌──────────────┐
                        │  admin-ui    │  │   user-ui    │
                        │   :3000      │  │    :3001     │
                        └──────┬───────┘  └──────┬───────┘
                               │                 │
                               └────────┬────────┘
                                        ▼
                              ┌────────────────────┐
                              │   dlmm-gateway     │  Spring Cloud Gateway
                              │   :8080            │  • JWT валидация
                              │                    │  • rate-limit (Redis)
                              └─────────┬──────────┘  • инжект X-User-Id
                                        │
       ┌──────────┬──────────┬──────────┼──────────┬──────────┬──────────┐
       ▼          ▼          ▼          ▼          ▼          ▼          ▼
   user-svc   token-svc  pool-engine  fee-svc   tx-svc   oracle    notification   admin-bff
    :8081      :8082       :8083      :8084     :8085    :8086        :8087        :8088
       │          │          │          │          │          │          │
       └──────────┴──────────┼──────────┴──────────┴──────────┴──────────┘
                             ▼
              ┌──────────────────────────────────┐
              │  PostgreSQL │ Redis │ Kafka │ ClickHouse  │
              │   :5432     │ :6379 │ :9092 │   :8123     │
              └──────────────────────────────────┘
```

## Сервисы

| Сервис | Порт | Назначение |
|---|---|---|
| `dlmm-gateway` | 8080 | Единая точка входа. JWT, rate-limit, инжект `X-User-Id`/`X-User-Role`/`X-Kyc-Status` |
| `dlmm-user-service` | 8081 | Регистрация, логин, KYC, управление пользователями |
| `dlmm-token-service` | 8082 | Каталог токенов и баланс пользователей |
| `dlmm-pool-engine` | 8083 | Ядро DLMM: bins, ликвидность, swap, переменная комиссия |
| `dlmm-fee-service` | 8084 | Расчёт и распределение комиссий |
| `dlmm-transaction-service` | 8085 | Реестр транзакций, settlement |
| `dlmm-price-oracle` | 8086 | Агрегация цен (MOEX, Binance) |
| `dlmm-notification-service` | 8087 | Уведомления через Kafka |
| `dlmm-admin-bff` | 8088 | Backend-for-frontend для админки |
| `dlmm-common` | (lib) | Общие DTO, исключения, BinMath, FeeCalculator |
| `dlmm-admin-ui` | 3000 | Панель администратора (React + AntD) |
| `dlmm-user-ui` | 3001 | Пользовательский кабинет (React + AntD) |

## Быстрый старт

```bash
# 1. Подготовить переменные окружения
cp docker/.env.example docker/.env
# (отредактировать docker/.env при необходимости)

# 2. Поднять весь стек (16 контейнеров)
cd docker && docker-compose up -d

# 3. Дождаться готовности (Spring сервисы стартуют ~40 сек)
docker-compose logs -f dlmm-gateway   # ждём "Started DlmmGatewayApplication"

# 4. Открыть UI
#    Admin:  http://localhost:3000   (admin@sber-dlmm.ru / Demo1234)
#    User:   http://localhost:3001   (ivanov@example.com / Demo1234)
```

Полная остановка с удалением данных:
```bash
cd docker && docker-compose down -v
```

## Разработка

**Один Spring сервис локально:**
```bash
mvn -pl dlmm-pool-engine -am package
DB_PASSWORD=dlmm_secret_dev_only JWT_SECRET=dev-secret-min-32-bytes-long-key-here \
  mvn -pl dlmm-pool-engine spring-boot:run
```

**Один тест:**
```bash
mvn -pl dlmm-pool-engine test -Dtest=SwapServiceTest
mvn -pl dlmm-pool-engine test -Dtest=SwapServiceTest#shouldExecuteSwap
```

**Фронтенд в dev-режиме:**
```bash
cd dlmm-admin-ui && npm install && npm run dev    # http://localhost:3000
cd dlmm-user-ui  && npm install && npm run dev    # http://localhost:3001
```
Vite проксирует `/api/*` → `http://localhost:8080` (gateway).

**Admin UI без бэкенда (на моках):**
```bash
cd dlmm-admin-ui && VITE_USE_MOCKS=true npm run dev
```

## Поток аутентификации

1. Клиент → `POST /api/v1/auth/login` (gateway пропускает без JWT, как и `/auth/register`, `/auth/refresh`).
2. `dlmm-user-service` отдаёт `accessToken` (HS384, 30 минут) + `refreshToken` (7 дней).
3. Все остальные запросы идут с `Authorization: Bearer <token>`.
4. `dlmm-gateway/JwtValidationFilter` декодирует JWT и **подставляет в upstream-запрос заголовки**:
   - `X-User-Id`
   - `X-User-Role` (`USER` | `MARKET_MAKER` | `ADMIN`)
   - `X-Kyc-Status` (`PENDING` | `VERIFIED` | `REJECTED`)
5. Каждый downstream-сервис своим `JwtAuthenticationFilter` валидирует токен повторно и строит `Authentication` для Spring Security.

## Документация API

После старта стека OpenAPI/Swagger UI у каждого сервиса:
- `http://localhost:8081/swagger-ui.html` — user
- `http://localhost:8083/swagger-ui.html` — pool engine
- `http://localhost:8088/swagger-ui.html` — admin BFF
- … и так далее по портам из таблицы выше.

## Стек

- **Java:** 21, Spring Boot 3.2.5, Spring Cloud 2023.0.1
- **Persistence:** PostgreSQL 16, Liquibase (`validate-on-migrate`), JPA `ddl-auto: validate`
- **Messaging:** Apache Kafka 7.6 (Confluent), топики `user-events`, `token-events`, `pool-events`, `fee-events`
- **Cache / rate-limit:** Redis 7
- **Analytics:** ClickHouse 24.1
- **Auth:** JJWT 0.12 (HS384)
- **API docs:** SpringDoc OpenAPI 2.5
- **DTO mapping:** MapStruct 1.5
- **Frontend:** React 18, TypeScript 5.4, Vite 5, Ant Design 5 + Pro Components, Zustand, TanStack Query 5, Recharts

## Известные ограничения и долг

- JWT-фильтр и provider дублированы в 7 сервисах (~939 строк) — кандидат на вынос в `dlmm-common-security`.
- Тесты сейчас только в `dlmm-common` (math) и `dlmm-pool-engine` (swap, liquidity, full-flow IT).
- Нет circuit-breaker / retry на inter-service вызовах (см. план в [CLAUDE.md](CLAUDE.md)).
- `init-db.sql` (470 строк) — общий на все сервисы; роадмап — раздельные Liquibase changelog'и.
- Frontend — нет общей design-system библиотеки, оба UI напрямую используют AntD.

Подробности по архитектуре и конвенциям: [CLAUDE.md](CLAUDE.md).

## Лицензия

Внутренний проект Сбербанка. Все права защищены.
