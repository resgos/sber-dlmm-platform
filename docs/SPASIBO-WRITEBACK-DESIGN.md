# SberSpasibo Write-Back — Design Sketch

**Sprint**: 6 ticket **#6.16**
**Status**: SA + BA draft. Sprint 7+ implementation gated on (a) Spasibo BU rev-share contract (Revenue Research item «a») landing, AND (b) Spasibo platform exposing the write-back API surface.
**Owner**: SA (this doc) + PO (commercial trek).
**Risk register**: closes R#29 (BA Sprint 5 acceptance §3 flagged write-back as scope-creep risk; documented here = downgraded from "open risk" to "spec'd backlog").

> **Motivation**: Sprint 5 #5.3+#5.4 shipped a ONE-WAY flow:
> Spasibo BU webhook → DLMM mint SSPAS → user converts to SRUB.
> Real-world Spasibo program also has a **reverse** flow: a purchase
> made through Sber-rails earns the user Spasibo points BACK from the
> merchant. This memo specifies how DLMM transactions (swaps, hedges,
> B2B settlements) can earn the user Spasibo cashback, and what the
> commercial + technical contract with Spasibo BU should look like.

---

## 1. Use case in plain words

User Ivanov holds 5M SRUB on DLMM. He executes a SRUB→SUSD hedge of 1M
through the Sprint 4 #4.1 hedge UI. **He earns Spasibo cashback** on
that operation:
- 1M SRUB × 0.5% cashback = 5000 SSPAS-units = 50 баллов

The 50 баллов land in **his real Spasibo wallet** (visible in
SберOnline app), NOT in DLMM SSPAS balance. He can then spend on
any Spasibo-partner merchant — or eventually withdraw to DLMM via the
existing Sprint 5 #5.3 webhook mint and convert to RUB.

This closes the loop: **DLMM ↔ Spasibo ↔ Sber rails**, with retail
user always staying inside Sber economy.

---

## 2. Two architectural options

### Option A — DLMM pushes earnings to Spasibo via webhook reverse

DLMM sends `POST /spasibo-api/v1/cashback/credit` on every qualifying
transaction. Spasibo BU receives, validates, credits the user's
real Spasibo wallet, fires an in-Spasibo-app notification.

**Pros:**
- Real-time UX — Ivanov sees the cashback in SberOnline within
  seconds of the DLMM hedge.
- Clear ownership: DLMM is the source of truth for what was earned.
- Idempotent via DLMM transaction id.

**Cons:**
- DLMM-side commits before Spasibo confirms → if Spasibo rejects (rate
  limit, fraud check), the user "earns" then sees nothing.
- Spasibo BU has to expose write API to DLMM (likely needs OAuth-scoped
  service account + IP allowlist).

### Option B — DLMM accrues internally, periodic batch settle

DLMM accumulates earnings in a `spasibo_pending_credits` table. Every
N minutes (or daily), a scheduler batches confirmed credits and pushes
to Spasibo via signed file transfer (СПФС-style) or batched API.

**Pros:**
- Decoupled from Spasibo BU latency / availability.
- Fits Spasibo BU operational pattern (most loyalty systems batch settle
  at end of day anyway).
- Lower API surface — one batch endpoint vs N per-tx ones.

**Cons:**
- Delayed UX — Ivanov doesn't see cashback for hours, sometimes a day.
  In banking 2026 this looks "old".
- Reconciliation breaks are harder to debug (batch failures = many users
  affected at once).

### Recommended: **Option A** with B as fallback

Real-time wins on UX. Document Option B as the recovery path if Spasibo
write API has rate-limit issues at peak.

---

## 3. Which DLMM operations earn cashback?

| Operation | Earn? | Rate | Notes |
|---|---|---|---|
| **SWAP** (regular pool swap) | ✓ | **0.10%** of amountIn (in SRUB-equivalent) | Most common; low rate keeps margin |
| **HEDGE** (Sprint 4 #4.1 path) | ✓ | **0.50%** of hedge size | Premium rate — incentivises treasurer-grade UX |
| **ADD_LIQUIDITY** | ✓ | **0.05%** of deposit amount | Reward LP onboarding |
| **REMOVE_LIQUIDITY** | ✗ | 0 | Already paying exit fee #3.4 |
| **CLAIM_FEE** | ✓ | **0.10%** of claimed | Reward active LP'ов |
| **B2B settlement** (#4.6) | ✗ | 0 | Corp accountants don't want loyalty points on books — they want lower fees instead |
| **Spasibo CONVERT** (#5.4) | ✗ | 0 | Would create infinite loop |
| **Self-restriction unblock** | ✗ | 0 | Compliance-sensitive |

Rates configurable per environment. Default total maximum per-user
cashback caps:
- Daily: 10,000 SSPAS-units (100 баллов)
- Monthly: 200,000 SSPAS-units (2000 баллов)

Cap protects against gaming (creating noise transactions just to farm
cashback). Caps enforced server-side before Spasibo push.

---

## 4. Commercial model — DLMM ↔ Spasibo BU rev-share

The cashback is paid by **Spasibo BU** from their marketing budget
(standard Spasibo program model — partners fund their own loyalty).
DLMM is the issuer of cashback events but Spasibo BU bears the cost.

What DLMM gets in return:
- **Customer acquisition**: every cashback transaction is a Spasibo-side
  notification "+50 баллов от Sber DLMM" — drives retail awareness.
- **Marketing co-op**: 30/70 cost share on quarterly retail campaigns
  (Spasibo 70%, DLMM 30%) targeting the 60M Spasibo MAU.
- **Premium-tier preferential**: Spasibo Premium subscribers get
  1.5× cashback on DLMM ops → drives DLMM volume from Premier segment.

What Spasibo BU gets:
- **Retention**: DLMM volume keeps high-value retail engaged with Sber
  ecosystem (vs. drifting to standalone fintech apps).
- **Bonus channel**: DLMM is a new earn-and-spend rail for the program.
- **Data**: aggregated DLMM transaction patterns (no PII) inform
  Spasibo's overall loyalty mechanics.

**Contract draft target**: 30-day rolling review of cashback budget
spend vs. retail-DLMM-MAU growth. Auto-pause if cashback spend exceeds
budget without proportional MAU growth.

---

## 5. Technical contract (Option A)

### 5.1 Spasibo write API (to be exposed by Spasibo BU)

Endpoint: `POST https://spasibo-api.sber.ru/v1/cashback/credit`

Auth: OAuth 2.0 client-credentials grant, DLMM as registered service
account; IP allowlist on Spasibo side limited to DLMM gateway egress.

Request:
```json
{
  "external_event_id": "dlmm-cashback-{uuid}",   // dedup anchor
  "sber_id": "12345678",                         // Spasibo user id (= sberId on DLMM)
  "amount_points": 50,                            // integer points
  "reason_code": "DLMM_HEDGE",                    // enum from Spasibo dictionary
  "description": "Кешбэк за хедж 1М SRUB → SUSD",
  "external_object_id": "tx-uuid-of-dlmm-tx",     // for accountant trace
  "earned_at": "2026-06-03T12:34:56Z"
}
```

Response:
```json
{
  "status": "ACCEPTED" | "REJECTED",
  "spasibo_transaction_id": "...",
  "rejection_reason": "RATE_LIMIT" | "USER_NOT_FOUND" | "DUPLICATE"
}
```

### 5.2 DLMM-side implementation

New service `SpasiboWriteBackService` in `dlmm-token-service`:
- `accrue(userId, dlmmTxId, amount, reasonCode)` — inserts row in
  `spasibo_writeback_queue` (PENDING). Idempotent by dlmmTxId.
- `@Scheduled` flush every 30s — picks up PENDING rows, calls Spasibo
  API, updates to ACCEPTED/REJECTED/RETRY. Retry policy: 3 attempts
  exponential backoff, then DEAD_LETTER + compliance email.
- Cap enforcement before insert — query last 24h sum, last month sum,
  reject if cap exceeded.

Hook into existing services:
- `SwapService.swap()` — after CONFIRMED, fire `spasiboWriteBack.accrue(...)`
- `HedgePage` swap path — already goes through `/pools/swap`, same hook
- `LiquidityService.addLiquidity` — same hook
- `LiquidityService.claimFees` — same hook (if it exists; Sprint 7+
  fee-claim flow)

### 5.3 Persistence

Liquibase changeset in `dlmm-token-service` (Sprint 7 #6.16-impl):
```
spasibo_writeback_queue:
  - id UUID PK
  - dlmm_tx_id UUID UNIQUE (idempotency)
  - user_id UUID
  - sber_id VARCHAR(32)
  - amount_points INT
  - reason_code VARCHAR(40)
  - status VARCHAR(20)  -- PENDING|ACCEPTED|REJECTED|RETRY|DEAD_LETTER
  - attempt_count INT DEFAULT 0
  - last_error VARCHAR(500)
  - spasibo_tx_id VARCHAR(64)
  - created_at TIMESTAMP
  - settled_at TIMESTAMP
  + indexes on status, user_id+created_at
```

---

## 6. Open questions

Blocking Sprint 7+ build kickoff:

1. **Spasibo BU write API timeline** — when does the endpoint exist in
   sandbox? Production?
2. **Cashback budget per quarter** — Spasibo BU sets the ceiling DLMM
   operates within. Q3 starter target: how many million ₽?
3. **Premium-tier rate multiplier** — Spasibo BU approval for 1.5×
   on Premium subscribers (or different number).
4. **Rate limits** — Spasibo write API throughput per second for DLMM
   service account.

Non-blocking but needed before GA:

5. **Reconciliation cadence** — daily Spasibo settlement file pulled
   into DLMM for diff against `spasibo_writeback_queue` ACCEPTED rows?
6. **Failed-credit recovery** — when Spasibo rejects with USER_NOT_FOUND
   (e.g., user closed Spasibo account), do we refund the DLMM-side fee
   that triggered cashback expectation?

---

## 7. Phasing

| Sprint | Deliverable |
|---|---|
| **6** | This memo + open-questions submission to Spasibo BU |
| **7** | Spasibo BU contract signature (target) + sandbox API access |
| **8** | Liquibase + entity + queue + scheduler (Option A real-time) |
| **9** | Hook into SWAP / HEDGE / ADD_LIQUIDITY / CLAIM_FEE paths |
| **10** | Beta with 100 internal Sber users, monitor cap-breach + reject rate |
| **11** | GA + retail-marketing co-op campaign launch with Spasibo BU |

---

## 8. Decisions captured

- [✓] **Option A real-time push** preferred over batch (UX vs ops
      reliability trade-off).
- [✓] **Cashback funded by Spasibo BU** (no DLMM-side P&L hit).
- [✓] **Rate schedule**: SWAP 0.10%, HEDGE 0.50%, ADD_LIQ 0.05%,
      CLAIM_FEE 0.10% — others excluded.
- [✓] **Caps**: 100 баллов/day, 2000 баллов/month per user.
- [✓] **Sprint 7 gate**: Spasibo BU contract signed before any code work.
- [✓] **PO submits §6 open questions** to Spasibo BU within 5 working
      days of Sprint 6 close.

---

## 9. Risks

| # | Risk | Mitigation |
|---|---|---|
| **R-SP-1** | Spasibo BU write API doesn't exist publicly — they may have to build it from scratch | Sprint 7 PO trek includes spec hand-off; fallback Option B batch via СПФС file transfer if no API by Sprint 8 |
| **R-SP-2** | Premium-tier 1.5× rate creates user-trust issue if it changes mid-campaign | Lock multiplier per quarter contractually; admin endpoint for emergency pause only |
| **R-SP-3** | Cap-gaming via micro-transactions (1₽ swaps spam) | Min-transaction threshold (100k ₽ for HEDGE, 10k ₽ for SWAP) enforced server-side |
| **R-SP-4** | Cashback-related compliance question — is cashback a "kickback" under 115-ФЗ? | Compliance memo (Sprint 7 #6.16-Compliance-followup) needed before launch |

---

*Recorded by: SA + BA. Sign-off: PO, Compliance lead.
Next: PO submits §6 questions to Spasibo BU. Sprint 7+ implementation
gated on contract.*
