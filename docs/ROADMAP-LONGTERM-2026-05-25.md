# Long-term roadmap — 2026-05-25

> Состояние после merge всех 9 PR'ов batch'а (Sprint 11-12 ship): 2FA UI,
> multi-user backend, auto-claim backend, price-impact preview, Helm
> chart, WAL-G backup, JWT runtime fail-fast, inline-style CI ratchet,
> + 11 dark-mode polish commits. Branch `claude/elated-elgamal-dba521`
> ready for review.
>
> Этот doc — следующие 4-6 sprints для долгой автономной работы.

---

## 0. Immediate residuals (week 1, ≤ 5 days)

Закрыть остатки от текущего batch'а.

| ID | Item | Effort | Owner | Source |
|----|------|--------|-------|--------|
| **#17** | Kafka bootstrap race — add `restart: unless-stopped` в docker-compose для всех Spring services | S | SRE | Sweep |
| **#19** | 2FA setup modal stuck on PENDING placeholder — make `TwoFactorSettings.tsx` await `beginSetup()` или subscribe via `useSyncExternalStore` | S | FE | Sweep |
| **HOT-4** | /hedge pair-selector cards still white in dark mode — identify class (likely custom inline bg), add CSS-var override | S | FE | UI test |
| **HOT-5** | JWT refresh-token flow — сейчас expires через 30 мин → user re-login. Add automatic refresh interceptor (POST /auth/refresh on 401, retry original request) | M | FE+BE | Sweep |
| **HOT-6** | apiClient 403 handling — fixed in 916acbd. Verify no false-positives на других endpoints. | S | FE | Sweep |
| **HOT-7** | ConfigProvider tokens reactive to themeStore — long-form fix to AntD CSS-in-JS specificity battle. Currently we beat it with !important sweep. Better: subscribe ConfigProvider to themeStore + flip entire token set on dark mode | M | FE | Task #13 root-cause |

**Total: ~8 days, 1 FE + 0.5 SRE + 0.5 BE.**

---

## 1. Sprint 13 (next 2 weeks) — Production deployment foundation

**Theme:** Pivot from "works in dev" to "ships to prod".

| ID | Item | Effort | Owner | Why now |
|----|------|--------|-------|---------|
| G-25 / TD-4 / TD-5 | **Helm chart → real K8s target.** Skeleton merged (PR #9). Need: GHCR push setup, KUBE_CONFIG secret, namespace + ingress controller, real values.yaml | L (8d) | SRE | Blocker для enterprise demo |
| G-35 | **DR failover drill** — Postgres + Kafka kill+restore in staging, document recovery time, alert thresholds | S (1d) | SRE | АВ + Dmitry трогали |
| TD-2 | **Spring Cloud Vault production integration** — skeleton merged (PR #11). Need: AppRole onboarding + Sber Vault namespace + key rotation runbook | M (3d) | SRE | Blocker for "production ready" |
| TD-6 | **WAL-G → real S3 bucket.** Script merged (PR #7). Need: Sber Cloud Object Storage bucket + IAM + cron rotation policy + monthly restore drill | M (3d) | SRE | Compliance |
| TD-1 | **Liquibase preConditions cleanup** — drop `CREATE TABLE` from `init-db.sql`, fully migrate to changesets. Removes the MARK_RAN hack. | L (5d) | BE | Tech debt sprint 12+ |
| **NEW-1** | **Resilience4j coverage gap** — fee-service + admin-bff don't have circuit-breaker on downstream calls. Add same pattern as pool-engine | S (1d) | BE | Discovered during merge |

**Total: ~21 days, 2 SRE + 1 BE. Output: cluster-ready platform.**

---

## 2. Sprint 14 — Trust Layer 3 + Commercial accelerators

**Theme:** Unblock corporate revenue + analytics.

| ID | Item | Effort | Owner | Revenue impact |
|----|------|--------|-------|----------------|
| G-29 | **SAML SSO** для corporate auth — Azure AD / Sber Federation IdP | M (3d) | BE | Corp tier blocker |
| G-01 | **Cohort analytics dashboard** (DAU/MAU/D7/D30 retention) — admin-bff endpoint + admin-ui chart page | M (3d) | BE+FE | Investor-ask |
| G-02 | **Quote-execute idempotency** — automated test covering: stale quote (TTL exceeded), double-execute, signature replay | S (1d) | BE | Risk register |
| **F-01** | **Telegram bot notifications** — margin call, swap fill, KYC update via @SberDlmmBot. Kafka consumer + Telegram Bot API client | M (3d) | BE | RU treasurer expectation |
| G-19 | Customer reviews surface — публичная страница "что говорят пилот-клиенты" | S (1d) | FE | Trust signal |
| G-17 | Simple-mode toggle — hide advanced features (rebalance/auto-claim/team) для retail. localStorage flag + conditional render | S (1d) | FE | Anna persona |
| **NEW-2** | **API rate-limit observability** — per-tier dashboard expansion: per-userId throttle counts, top-throttled endpoints chart | S (1d) | BE+FE | Discovered during F-15 review |

**Total: ~13 days, 1.5 BE + 1 FE.**

---

## 3. Sprint 15 — UX + Onboarding + i18n

**Theme:** Reduce activation friction.

| ID | Item | Effort | Owner |
|----|------|--------|-------|
| G-13 | **Video onboarding** (3 видео × 2-3 мин) + FE embed | M (5d) | Marketing + FE |
| G-24 | **1С коннекторы** — pre-built mappings для УПП 1.3 / ЗУП 8.3 / Бух 3.0 (CSV/XML export endpoints) | M (5d) | BE |
| G-23 | **Pool comparator pro metrics** — 30d volatility / max drawdown / Sharpe ratio (новые ClickHouse rollups) | S (1.5d) | BE+FE |
| G-28 | **User-actions audit log** — extend Sprint 8 `@AdminAudit` aspect to capture user-side trades + claims for compliance | M (2d) | BE |
| **NEW-3** | **EN i18n full sweep** — Sprint 8 wired react-i18next on Swap only. ~80% strings still hardcoded RU. Mechanical sweep + EN bundle | M (3d) | FE |
| **NEW-4** | **Health Score calibration tune** — current 20% target APY hardcoded. Replace with per-pool rolling 30d median (data-driven) | S (1d) | BE |

**Total: ~17 days, 1.5 BE + 1.5 FE + 1 Marketing.**

---

## 4. Sprint 16+ — Compliance + Enterprise moats (Q3-Q4 2026)

**Theme:** Multi-year regulatory + institutional differentiation.

| ID | Item | Effort | Owner | Blocker |
|----|------|--------|-------|---------|
| F-17 | **Real-time AML SAR auto-filing** Росфинмониторинг (115-ФЗ) — RFM API integration | L (15d) | BE + Compliance | Memo on filing format |
| F-18 | **MOEX-style market surveillance** — wash-trade / layering / spoofing detection on swap flow | L (10d) | BE | None |
| G-30 | **НРД segregated custody** partnership OR own depositary license | XL (12-18 мес) | BD + Legal | Regulatory |
| G-32 | **On-premise deployment** option (Helm + on-prem secrets + air-gap docs) | XL (15d) | SRE | Customer T&M |
| G-33 | **White-box pentest** (Positive Technologies) | external | Procurement | None |
| G-31 | **HSM-backed signatures** для multi-sig (Sber HSM integration) | M-L | BE | Sprint 15 multi-sig prereq |
| G-38 | **Compliance split matrix** legal doc — finalise responsibility map | M | Legal | None |
| G-39 | **Force-majeure protocol** with third-party adjudication | M | Legal | None |
| **F-13** | **SLA MM contracts** (Sber Treasury как guaranteed MM) — 3 contracts × 50M ₽/yr | M (paper) | PO | Sber Treasury BU sign-off |
| **F-25** | **Sber Online SSO retail** — full integration after BU contract | M (3d code) | BE | Sber Online BU contract |

---

## 5. Continuous tech debt (background)

Не привязано к sprint, делается по 1-2 items в любую wave.

| ID | Item | Effort |
|----|------|--------|
| ESLint rule for inline `fontSize:<number>` / `borderRadius:<number>` — enforce HOT-3 baseline | S | FE |
| Caffeine → Redis cache for pool-engine TokenServiceClient (cross-instance cache invalidation) | M | BE |
| Distributed tracing (Sleuth + Zipkin) — currently log-based only | M | SRE |
| `dlmm-ui-common` workspace package — deduplicate KpiTile / TokenPairChip / format helpers between admin-ui + user-ui (currently scripts/check-ui-shared-drift.mjs catches divergence — proper fix is npm workspace) | L | FE |
| Replace per-service JwtAuthenticationFilter copies (done) — periodic check for any new per-service copies | S | BE |
| Pool detail loadTime optimization — currently re-fetches /pools every entry. Cache the listing query | S | FE |

---

## 6. Parking lot (explicit "won't build now")

| Idea | Why parked | Re-evaluation trigger |
|------|------------|------------------------|
| Zap In/Out (deposit any token, auto-swap) | Needs router; >2 weeks | If pilot LPs ask |
| Auto-rebalance bots / scheduled rebalance jobs | Defer until manual rebalance field-validated | Sprint 17 |
| Farming rewards overlay | Not running rewards programme | If marketing budget freed |
| Concentrated swap routing across N pools | Single-pool covers 95% volume | If >5% volume in multi-hop |
| Limit Order partial fills | Limit orders not yet shipped | After F-02 (limit orders, Sprint 14+) |
| Gas-less swaps via paymaster | Banking sponsorship covers it | Never (not on roadmap) |
| WebSocket live price stream | Polling 10s is fine for retail UX | If institutional client asks |
| NFT receipts for LP positions | Cosmetic, no PO demand | Never |
| Cross-chain bridge (TON, BNB) | Sber doesn't operate cross-chain | Never (strategic) |
| Governance token + DAO | Not commercially needed | Never (legal) |

---

## 7. Revenue projection updated post-batch

После завершения этого batch'а (Sprint 11-12 ship), forecast не меняется — мы на траектории original Sprint 8 plan, но с укреплённой trust layer (2FA + multi-user + audit log path) которая открывает Q3 enterprise pipeline.

| Quarter | Run-rate | Δ vs prior plan |
|---------|----------|-----------------|
| Q3 2026 (Sprint 13 close) | 800M-1.1B ₽/yr | unchanged |
| Q4 2026 (Sprint 14-15) | 1.5-2.0B ₽/yr | unchanged |
| Q1 2027 (Sprint 16) | 2.2-2.8B ₽/yr | +0.2B from F-17 AML moat |
| Q2 2027+ | 3.5-4.5B ₽/yr | unchanged |

**Single biggest unlock left:** F-13 SLA MM contracts (3 × 50M ₽/yr quick) + F-25 Sber Online SSO (100K retail signups × 0.1% conversion). Both contract-blocked, not code-blocked.

---

## 8. Resource allocation summary (next 8 weeks)

| Team | Sprint 13 | Sprint 14 | Sprint 15 | Total (8w) |
|------|-----------|-----------|-----------|-----------|
| Backend Java | 13d | 10d | 12d | 35d (≈ 1.75 FTE × 4w each) |
| Frontend | 4d | 5d | 8d | 17d (≈ 1 FTE × 4w + buffer) |
| SRE | 14d | 1d | 0 | 15d (≈ 2 FTE × 1 sprint, then idle) |
| Marketing | 0 | 0 | 5d | 5d |
| PO/Legal | 0 | 0 | 0 | 0 (parallel track) |

**Bottleneck:** SRE during Sprint 13 (Helm + Vault + WAL-G all converging). Mitigate: hire 1 contract SRE for 4 weeks OR shift Helm cluster work to managed service (Sber Cloud Managed Kubernetes).

---

## 9. Risks register (top 5)

| Risk | Severity | Mitigation |
|------|----------|-----------|
| Sber Online BU contract delay → F-25 retail SSO blocked | HIGH | Pitch as Q4 not Q3 deliverable; emphasize F-13 SLA contracts as alternative pipeline |
| SRE bottleneck Sprint 13 | HIGH | Plan for contract SRE 4 wk OR managed K8s |
| RFM API for AML SAR не получен | MEDIUM | F-17 ship as shell (decision-engine + log queue) первой, integration later |
| User retention low after pilot — Anna persona "не понимаю" | MEDIUM | G-13 video + G-17 simple-mode prioritise Sprint 15 |
| Pre-existing failures in test suite (autoClaim, positionAlerts) | LOW | Already fixed (commit 06f964c) — monitor CI |

---

## 10. Decision points (need PO input)

1. **Sprint 13 SRE staffing** — hire contractor OR shift to Sber Cloud Managed K8s? (~30M ₽/yr difference)
2. **G-30 НРД custody** — start partnership exploration now (12-18 мес lead time) или ждать Sprint 16?
3. **F-17 AML SAR shape** — shell-only Sprint 16 + integration Sprint 18, или wait for RFM API signed?
4. **EN i18n** — full sweep (Sprint 15) или just key surfaces (Swap, OTC desk for foreign counterparties)?
5. **Open-source the Helm chart** — accelerate community / pilot ops adoption, или keep closed?

---

*Автор: Claude · 2026-05-25 · revision 1 (post-batch ship). Companion to
`docs/BACKLOG-2026-05-21.md` (P0-P3 + TD register),
`docs/NEW-FEATURES-BACKLOG-2026-07-08.md` (F-01..F-28),
`docs/demo/SIMULATED-DEMOS-FEEDBACK-2026-05-22.md` (G-01..G-40 growth points).*
