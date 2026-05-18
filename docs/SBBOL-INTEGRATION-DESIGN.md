# SBBOL ↔ DLMM Integration — Design Sketch

**Status**: Sprint 4 #4.5 — design only, no code in this sprint.
**Owner**: SA (this doc) + Sber integrations BU (handshake spec).
**Audience**: Sber integrations BU, DLMM platform team, compliance.
**Open questions block**: see §7 — needs Sber integrations response before Sprint 5 build.

> SBBOL = «СберБизнесОнлайн» — Sber's corporate banking portal. The 800k+
> Russian legal entities that already bank on SBBOL are the natural
> first audience for DLMM corp products (FX hedge, B2B settlement,
> LP placement). This memo defines what the integration shape looks
> like so we can negotiate scope with the Sber integrations BU on
> a known footing.

---

## 1. Scope & non-scope

**In scope (target: Sprint 5 first cut, Sprint 6 GA):**

| Capability | Why first |
|---|---|
| **Auth handoff** — SBBOL session → DLMM JWT without re-login | Removes biggest UX friction; bank treasurers won't tolerate a second password |
| **Balance lookup** — read corp's RUB account balance for hedge sizing | The "you have N rubles" stat in HedgePage.tsx (#4.1) must come from real SBBOL balance, not the toy `user_balances` table |
| **Settlement debit/credit** — DLMM hedge / B2B settlement debits SBBOL RUB account, credits DLMM token wallet (and vice versa) | Closes the loop. Without it, DLMM tokens never round-trip back to fiat |
| **Operation log feed** — DLMM transactions appear in SBBOL operation history | Bookkeeping parity — accountant sees one statement, not two |

**Out of scope (deliberately deferred):**

- SBBOL UI embed of DLMM widgets — needs separate UX track + Sber design system review (Sprint 7+).
- Multi-signature corp approval flows — most corp ops have 2-of-3 approval; we'll need SBBOL approval webhook in Sprint 6.
- Cross-product (kredits, deposits, FX forwards) bundling — Sber-wide commercial play, not platform integration.

---

## 2. Auth handoff

### 2.1 Current state (today, pre-integration)

DLMM user logs in via `POST /api/v1/auth/login` with email+password against
`dlmm-user-service`. JWT is HS256, secret in `dlmm.jwt.secret`, lifetime
60min + refresh 7d. Frontend stores in Zustand `authStore`.

This works for retail / standalone corp pilot but is unacceptable for
production SBBOL integration: corp ops won't type a second password.

### 2.2 Proposed flow

**Option A — OIDC delegate (preferred).** SBBOL becomes an OIDC IdP for
DLMM via Sber's existing internal OIDC platform (the same one SberID
already federates with).

```
┌──────────┐   1. user clicks "Open DLMM"        ┌──────────────┐
│  SBBOL   │ ─────────────────────────────────▶  │  Sber OIDC   │
│ Web UI   │   inside SBBOL header                │  IdP         │
└──────────┘                                      └──────┬───────┘
     ▲                                                   │
     │                                                   │ 2. authorize
     │                                          (silent, SSO cookie)
     │                                                   │
     │ 6. redirected back with                           ▼
     │    DLMM session cookie                     ┌──────────────┐
     │                                            │   DLMM       │
     └─────────────────────────────────────────── │   Gateway    │
            5. /auth/oidc/callback?code=...       │   /auth/oidc │
                                                  └──────┬───────┘
                                                         │
                                3. id_token+access_token │
                                  4. claims:             ▼
                                     sub (sber_corp_id)  ┌──────────────┐
                                     org_inn             │ dlmm-user-   │
                                     role (operator|     │ service      │
                                           approver|     │ (upsert user │
                                           viewer)       │  + issue JWT)│
                                                         └──────────────┘
```

Token-issuance step (4) is the integration point. dlmm-user-service:
- Resolves OIDC sub → existing DLMM user, or auto-provisions one
  (with `kyc_status=VERIFIED` because SBBOL onboarding already implies KYB).
- Maps SBBOL role → DLMM role: SBBOL `Руководитель` → `ADMIN`,
  `Бухгалтер` → `USER` with hedge/transfer ability, `Просмотр` → read-only `USER`.
- Issues the same DLMM JWT used by direct login (no new auth scheme inside
  DLMM — only the entry point changes).

**Option B — Reverse: DLMM as inner iframe.** SBBOL renders a DLMM
sub-page in an iframe and forwards the SBBOL session cookie. Rejected:
cookie scope mismatches, CSP nightmares, accessibility regressions.

**Option C — Shared backend session table.** Both apps query a shared
Postgres `sessions` table for token validation. Rejected: cross-cutting
state, tight coupling to SBBOL's session model, scaling concerns
(SBBOL does 8M sessions/day).

### 2.3 OIDC parameters to confirm with Sber integrations BU

| Parameter | Default proposal | Sign-off needed |
|---|---|---|
| OIDC issuer URL | `https://oidc.sber.ru/realms/sbbol` | Sber integrations |
| Required scopes | `openid profile sbbol_corp_role inn` | Sber integrations + compliance |
| `prompt=none` allowed (silent SSO)? | Yes for already-authed sessions | Sber integrations |
| Token signing | RS256 with JWKS endpoint published | Sber integrations |
| Max ID-token lifetime | 5min (we re-issue our own JWT) | DLMM |
| Refresh token rotation | Yes, single-use | Sber integrations |
| `back_channel_logout_uri` | `https://dlmm.sber.ru/auth/oidc/logout` | DLMM |

### 2.4 Logout semantics

SBBOL logs the operator out → SBBOL OIDC IdP fires back-channel logout
to DLMM gateway → DLMM revokes the JWT (Redis denylist by `jti`).
Outstanding tab on DLMM gets 401 on next API call → frontend redirects
to SBBOL login.

**Trap**: we have NO revocation today — JWT lives the full 60min. Need
to add a Redis denylist before SBBOL integration goes live. Tracked
as Sprint 5 R-new.

---

## 3. Balance lookup

### 3.1 What we need

Two scopes:
- **Read** the corp's RUB account balance to populate "exposure to hedge"
  on `HedgePage.tsx` (Sprint 4 #4.1, currently reads our toy `user_balances`).
- **Read** the corp's FX account balances (USD/CNY/EUR) so post-hedge
  we can show "you now hold X SUSD ≈ Y USD on your dollar account".

### 3.2 Proposed API shape

SBBOL exposes `GET /api/v3/accounts/{accountId}/balance` (existing — used by
SBBOL's own dashboard widgets). Integration is straight WebClient consumer
from dlmm-token-service, wrapped in a new `SbbolClient` class similar
to existing `TokenServiceClient` (with Resilience4j circuit-breaker).

Cache layer: 30-second Caffeine in dlmm-token-service. Treasurers don't
need sub-second freshness; over-fetching SBBOL is rate-limited at 100
req/min/client.

### 3.3 Account mapping

DLMM `user_balances` row needs an SBBOL account anchor:

```sql
ALTER TABLE user_balances ADD COLUMN sbbol_account_id VARCHAR(34);
ALTER TABLE user_balances ADD COLUMN sbbol_synced_at TIMESTAMP;
CREATE INDEX idx_user_balances_sbbol_account ON user_balances(sbbol_account_id);
```

Sync job: every 60s, dlmm-token-service iterates corp users with
`sbbol_account_id IS NOT NULL`, fetches live balance, updates the row
+ `sbbol_synced_at`. UI shows the stamp ("Updated 12s ago") so the
operator knows freshness.

The DLMM `user_balances.available` becomes a CACHED VIEW of SBBOL truth.
For hedge sizing we use this cached value (predictable, no race).
For actual settlement (next section) we re-query at-the-moment.

---

## 4. Settlement debit/credit

This is the hard one. SBBOL has a payment-creation API but it's
designed for human-approved transfers, not machine-initiated.

### 4.1 Two transaction primitives to map

| DLMM op | SBBOL equivalent | Settlement direction |
|---|---|---|
| FX hedge (SRUB → SUSD swap) | Debit corp RUB account, credit DLMM platform RUB nostro | One-way: RUB out, tokenized USD held in DLMM wallet |
| Hedge unwind (SUSD → SRUB swap) | Reverse | One-way: tokenized USD burned, RUB credited back |
| B2B settlement (corp A → corp B SRUB) | Internal DLMM transfer, then EOD net-settle if either party draws fiat | Hybrid — most stays inside DLMM, periodic fiat reconciliation |

### 4.2 Operation model — recommended

Use **SBBOL Standing Order API** (corp pre-authorizes DLMM as a debit
destination, single-signature for amounts ≤ N RUB, two-signature above).
Implementation:

1. **One-time onboarding (per corp client):**
   - Corp signs a "DLMM debit mandate" agreement (paper, KYB-enforced).
   - SBBOL creates a standing-order placeholder with DLMM as beneficiary,
     parameter "max single debit" = corp-configured ceiling
     (defaults to 10M RUB; matches our `max_single_swap_nominal_x/y` from #4.2).
   - DLMM stores the `sbbol_mandate_id` per user.

2. **Per-hedge flow:**
   - DLMM HedgePage gets confirmation click → POST to
     `dlmm-transaction-service /b2b/settlements` (#4.6 path, extended
     to accept `mandate_id`).
   - Transaction service:
     - Pre-checks: mandate active, amount ≤ ceiling, balance lookup OK.
     - Inserts `b2b_settlements` row PENDING (#4.6 entity, gain a
       `sbbol_mandate_id` column).
     - Calls `POST /sbbol/api/v3/payments/initiate` with mandate ref.
     - SBBOL returns immediate `accepted` (synchronous) — if any
       multi-sig is needed it goes into `pending_approval` and we
       webhook back later.
     - On `accepted` → execute the swap on pool-engine, credit
       tokenized USD, mark `b2b_settlements` COMPLETED.
     - On `pending_approval` → poll mandate status every 30s for up
       to 24h, then auto-cancel + refund (no balance moved).

3. **Reconciliation:**
   - Daily at 23:50, dlmm-token-service runs a recon job:
     pull SBBOL transaction list since last cursor, match against
     our `b2b_settlements` + transaction tables by `mandate_id`,
     flag any mismatch into `recon_breaks` table.
   - Compliance gets the alert if any break > 30min unresolved.

### 4.3 Failure modes (the dangerous quadrant)

| Scenario | DLMM state | SBBOL state | Resolution |
|---|---|---|---|
| SBBOL debit succeeds, DLMM swap fails | tokens NOT minted | RUB debited | Auto-credit refund via reverse SBBOL standing-order call within 60s; loud alert |
| DLMM swap succeeds, SBBOL debit fails | tokens minted | RUB unchanged | **This must never happen** — guard by pre-debiting BEFORE swap (eager pessimistic). Sprint 5 design: two-phase with SBBOL hold/commit. |
| Both succeed but recon shows wrong amount | tokens minted at amount X | RUB debited at amount Y | Manual recon; flag in `recon_breaks`; compliance pages. Saga / compensating action — Sprint 6+. |

This is why #4.6's `[RECONCILE]` error_message tag exists today (prototype
without SBBOL): the same operator workflow scales up.

---

## 5. Operation log feed

SBBOL has a "Операции по счёту" tab. Corp accountant expects to see
DLMM operations there (debit, credit, fee) as if they were normal
bank operations.

### 5.1 Proposed approach

Two paths considered:

**Path A — DLMM pushes operation rows to SBBOL via API.**
- Pros: real-time visibility.
- Cons: requires SBBOL operation-write API which currently doesn't
  exist for third parties.

**Path B — Standing-order debit IS the operation entry.**
- The SBBOL standing-order payment created in §4.2 naturally appears
  in SBBOL's transaction log with description prefix "DLMM:".
- DLMM enriches the SBBOL payment metadata field with our internal
  txn id so accountants can cross-reference.
- Pros: zero new API surface, leverages existing SBBOL operation row.
- Cons: doesn't surface DLMM-internal events (fees accrued, margin call).

**Recommendation:** Path B for v1. Path A is a future RFC once SBBOL
opens a third-party operation-write API.

DLMM fees / margin call alerts go to email + SBBOL notification panel
(existing SBBOL notification API does accept third-party events).

---

## 6. Compliance & regulatory posture

| Concern | Where landed | Owner |
|---|---|---|
| KYB inheritance from SBBOL | Auto-provisioned DLMM user inherits `kyc_status=VERIFIED` because SBBOL already KYB'd | Compliance sign-off needed before Sprint 5 build |
| AML — DLMM transactions reportable to Rosfinmonitoring? | TBD — depends on reg-category of B2B settlement (Sprint 3 #3.F memo not yet returned) | Compliance |
| Data residency — SBBOL session data crossing into DLMM service boundary | All within Sber data perimeter, no cross-border. Logged in audit. | Compliance |
| OFAC sanctions screening — does DLMM re-screen or trust SBBOL? | Trust SBBOL for sender, re-screen receiver. SBBOL `sanctions_status` claim in OIDC token. | Compliance + Sber sanctions team |
| GDPR-equivalent (152-ФЗ) — DLMM stores SBBOL `sbbol_account_id`. Subject access rights apply. | Existing DLMM /profile endpoint extended in Sprint 5. | Privacy officer |

---

## 7. Open questions — needs Sber integrations BU response

Blocking Sprint 5 build kickoff (cannot start code until these are answered):

1. **OIDC issuer URL + scopes (§2.3 table)** — confirm or propose
   alternative.
2. **Standing-order API access** — does SBBOL grant third-party (DLMM)
   standing-order creation, or must each corp create theirs manually
   through SBBOL UI before DLMM can use them? (huge UX difference).
3. **Payment-initiate sandbox** — when can DLMM get a sandbox tenant
   for testing? Realistic latency to first test transaction?
4. **Operation log enrichment** — what metadata fields are exposed in
   the SBBOL operation row? Can we get a free-text 256-char description?
5. **Notification panel third-party publish** — API surface for DLMM
   to drop "margin call on position X" into the SBBOL notification panel?
6. **Recon API** — daily transaction-list-since-cursor with what
   pagination + max-window semantics?

Non-blocking but want before Sprint 6 GA:

7. **SLA** — what's the SBBOL payment-initiate p95 latency? If > 2s
   we need to make the hedge flow asynchronous in UI.
8. **Throughput** — peak corp-hour we expect SBBOL to absorb DLMM
   traffic without rate-limiting.
9. **Sandbox-to-prod promotion** — what's the formal review gate?

---

## 8. Risk register (this integration only)

| Id | Risk | S × L | Mitigation |
|---|---|---|---|
| SBBOL-R1 | Standing-order API access denied to third parties → no machine-initiated debits → reverts to corp-clicks-through-SBBOL flow (bad UX, 30s per hedge) | M × L | §7 q2 unblocks; fallback plan documented |
| SBBOL-R2 | OIDC scope `sbbol_corp_role` doesn't exist as named → role mapping (§2.2) needs custom claim → blocked on Sber OIDC roadmap | M × M | Stub with `viewer` minimum role; refine via integration meetings |
| SBBOL-R3 | Recon mismatch storm on go-live (off-by-decimal rounding) | H × L | Shadow-recon for 2 weeks before write-enable; alerting on >5 breaks/day |
| SBBOL-R4 | SBBOL session timeout misaligned with DLMM JWT lifetime → user-facing 401s | L × M | Back-channel logout (§2.4) + UI reactive 401 → silent re-auth via OIDC |
| SBBOL-R5 | Compliance memo (§6 row 2) lands "you ARE a broker-dealer" → entire DLMM commercial model needs reframing | M × M | Track via 3.F / 4.5 memo chain; parallel commercial work continues |
| SBBOL-R6 | Sandbox tenant assignment takes 6+ weeks (typical Sber integrations cycle) → blocks Sprint 6 GA | H × M | Apply for sandbox NOW (this sprint close); don't gate Sprint 5 code on sandbox |

---

## 9. Implementation phasing

| Sprint | Deliverable |
|---|---|
| **4** (current) | This design doc (#4.5). Submit §7 questions to Sber integrations. |
| **5** | OIDC handoff code (gateway + user-service). Balance lookup via new `SbbolClient` in token-service. Read-only mode — no payments. Sandbox first. |
| **6** | Settlement debit/credit via standing-order. `b2b_settlements` gains `sbbol_mandate_id`. Daily recon job. Compliance sign-off gate. |
| **7** | GA — production tenant promotion. Operation log enrichment (Path B). Notification panel integration. |
| **8+** | Multi-sig approval workflow. SBBOL UI embed RFC. |

---

## 10. Decisions captured in this sprint

- [✓] OIDC delegation chosen over iframe (§2.2 Options).
- [✓] Path B (standing-order = operation log) chosen for operation feed (§5.1).
- [✓] Pre-debit (eager pessimistic) for settlement to avoid the dangerous
      "tokens minted, RUB unchanged" failure mode (§4.3).
- [✓] Cache-and-refresh model for balance lookup, not query-on-every-render (§3.2).
- [✓] Sprint 4 deliverable scope = this memo + §7 questions submission.
      Code starts Sprint 5 ONLY after §7 q1, q2, q3 are answered.

---

*Recorded by: SA. Next action: PO submits §7 to Sber integrations BU
this week. Sprint 5 backlog adds 5.A "SBBOL sandbox provisioning"
and 5.B "OIDC handoff code" once §7 q3 lands a date.*
