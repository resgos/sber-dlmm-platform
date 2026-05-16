# Sber DLMM — Domain Glossary

One-pager so anyone joining a demo or a code review can keep up with the
vocabulary. Terms are grouped by concept, not alphabetically.

> Audience: PO, IT-lead, sysAnalyst, new dev. Engineering-deep concepts get
> a short version here and a link into the codebase for full detail.

---

## Liquidity mechanics

| Term | Meaning |
|---|---|
| **DLMM** | Dynamic Liquidity Market Maker. Concentrated-liquidity AMM where liquidity is bucketed into discrete price bins (vs. Uniswap-v2's continuous curve). Trades cross one bin at a time. |
| **Bin** | One price slot in a pool. Identified by `binId` (integer). Each bin holds reserves of token X and token Y at a single price `P_bin = (1 + binStep) ^ (binId - referenceBinId)`. |
| **Bin step** | The constant percentage gap between adjacent bins, in basis points. `binStep: 25` ⇒ each bin is 0.25% above the previous. Smaller = more granular = more capital efficient; larger = fewer bins = cheaper crosses. |
| **Active bin** | The bin currently containing the market price. Swaps consume from this bin first, then walk to the next if it empties (`bins_crossed > 1`). |
| **Liquidity shares** | LP's claim on a single bin, denominated in a synthetic unit so multiple LPs in the same bin can be tracked proportionally. |
| **Position** | An LP's stake across a contiguous range of bins (`bin_range_min`..`bin_range_max`). Strategy decides how shares are distributed across that range. |
| **Strategy** | `SPOT` = equal weight per bin; `CURVE` = bell-shaped, more weight near the active bin; `BID_ASK` = barbell, weight pushed to the edges. |
| **Composition factor** | Per-bin ratio of token X reserves to total bin reserves. Tells you how "leaned" a bin is: 0.5 = balanced, 1.0 = pure X (above market), 0.0 = pure Y (below market). |

## Fees & oracle

| Term | Meaning |
|---|---|
| **Base fee (bps)** | Static fee charged on every swap, in basis points. `baseFeeBps: 25` ⇒ 0.25%. Stored on the pool. |
| **Variable fee** | Dynamic add-on driven by recent volatility. Capped by `maxVariableFeeBps`. Decays back to zero over `decayPeriodSeconds`. Implementation in `FeeCalculator`. |
| **Volatility accumulator** | Per-pool counter that grows with bins-crossed per recent swap, used as the input to the variable fee curve. |
| **Fee growth** | Per-bin accumulator (`fee_growth_x`, `fee_growth_y`) — used to compute each LP's earned-but-unclaimed fees without rewriting every position on every swap. |
| **Fee accrual** | Row in `fee_accruals` recording fees earned by one position from one swap. Created by pool-engine on swap settlement, claimed (zeroed `unclaimed_fee_x/y`) on RemoveLiquidity. |
| **Price oracle** | External price feed for each token (MOEX_USDRUB, BINANCE_BTC, etc). Stored in `tokens.price_oracle_id`. dlmm-price-oracle aggregates and exposes via `/api/v1/oracle/**`. |

## Tokens & balances

| Term | Meaning |
|---|---|
| **Sber-token** | Internal token symbol prefixed with S: `SRUB`, `SBTC`, `SGOLD`, `SBER` (Sber stock), `SMOEX` (MOEX index). Decimals vary (2 for SRUB, 8 for SBTC). |
| **Token type** | `FIAT_BACKED`, `COMMODITY_BACKED`, `UTILITY`, `INDEX_TOKEN`. Today these are display labels; no special business logic switches on them yet. |
| **Mintable / burnable** | Admin capability flags. `SRUB` is mintable+burnable (we control supply); `SBER` is neither (it's a wrapper). |
| **User balance** | Off-chain ledger row: `(user_id, token_id) → available + locked`. Locked is reserved for in-flight orders but not yet executed (Sprint 2 work). |
| **KYC status** | `PENDING` → `VERIFIED` → optional `REJECTED`. Swaps & add-liquidity require VERIFIED. Enforced in pool-engine via UserServiceClient.isKycVerified (fail-closed on outage). |

## Operations & status

| Term | Meaning |
|---|---|
| **Pool status** | `ACTIVE` (trades allowed), `PAUSED` (admin-pause, manual resume), `EMERGENCY_SHUTDOWN` (price-feed broke or major loss; positions can withdraw, no swaps), `CLOSED` (drained). |
| **Idempotency key** | Client-supplied unique string on every state-changing call. Stored on `transactions.idempotency_key`; duplicate keys return the original response without re-executing. |
| **Outbox** | (Sprint 2) Transactional outbox pattern: token-service writes balance mutation + outbox row in one DB tx; a dispatcher publishes to Kafka. Prevents lost events on crash between DB commit and Kafka send. |
| **Circuit breaker** | Resilience4j wrapper on outbound HTTP. Trips after 50% failure rate in a sliding window; rejects further calls for 10s; tests with 3 calls in half-open; closes when healthy. |

## Architecture

| Term | Meaning |
|---|---|
| **Gateway** | `dlmm-gateway:8080` — Spring Cloud Gateway. Single public entry. Validates JWT, injects `X-User-Id` / `X-User-Role` headers, applies Redis-backed rate limiting (100 rps, 150 burst). |
| **BFF** | Backend-for-Frontend. `dlmm-admin-bff:8088` aggregates downstream endpoints for the admin dashboard. Frontend gets one curated response per page. |
| **Service-to-service auth** | `BearerTokenForwardingFilter` (in dlmm-common) copies the inbound `Authorization` header onto outbound WebClient calls. Lets pool-engine call token-service "on behalf of" the original user. |
| **Liquibase** | Schema migration tool. Currently runs in `validate-on-migrate` mode (no schema mutation at startup) — schema is provisioned by `docker/init-db.sql` at first container boot. |
| **TWAP** | Time-Weighted Average Price. `price_feeds.twap_price` is the moving average over a window; used for fairness checks vs. `current_price`. |

## Demo-day specifics

| Term | Meaning |
|---|---|
| **Seed catalog** | 4 users, 22 pools, 18 tokens (SRUB paired with Russian blue-chips, MOEX index tokens, FX-pegs, commodities). Seeded in `docker/init-db.sql` + `02-extended-assets.sql` + `03-seed-trading-history.sql`. |
| **Demo password** | `Demo1234` for every seed user. Admin: `admin@sber-dlmm.ru`. |
| **Default ports** | gateway 8080, user 8081, token 8082, pool 8083, fee 8084, transaction 8085, oracle 8086, notification 8087, admin-bff 8088, admin-ui 3000, user-ui 3001. |

---

*Last refreshed: Sprint 1 (2026-05-16). Owner: IT Lead. Updates land in this file as new domain concepts ship — keep it < 2 pages.*
