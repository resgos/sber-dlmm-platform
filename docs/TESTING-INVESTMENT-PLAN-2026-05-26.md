# Testing Investment Plan — Business Outcomes (2026-05-26)

> Какие test-инвестиции нужны платформе чтобы (а) выкатиться в pilot
> без surprise-багов, (б) пройти Sber Security audit, (в) защититься
> от регрессий по мере роста кодовой базы и команды.
>
> Это не "написать побольше тестов". Это **бизнес-приоритизация
> тест-инвестиций** через призму риска и ROI: где недотест стоит
> дороже всего, и где автоматизация окупится быстрее всего.
>
> Companion docs: `docs/RISK-REGISTER.md` (18 рисков),
> `docs/SPRINT-PLAN.md` (sprint backlogs), `docs/ROADMAP-LONGTERM-2026-05-25.md`.

---

## 1. Сегодняшний baseline (что уже есть)

| Слой | Что есть | Качество |
|------|----------|----------|
| Unit tests (Java) | 46 файлов · `dlmm-common` + `dlmm-pool-engine` + `dlmm-admin-bff` | 🟡 нет JaCoCo coverage → метрики нет |
| Unit tests (FE) | 30 файлов · Vitest в обоих UI | 🟡 покрывает только stores + lib helpers, не pages |
| Integration | `FullSwapFlowIT` Testcontainers · 1 сценарий | 🔴 1 сценарий из ~20 critical paths |
| Load | k6 `baseline.js` + `single-pool-lock.js` · pinned numbers in README | 🟡 не запускается в CI, ручной trigger |
| Security | Trivy weekly image scan · CI workflow `container-scan.yml` | 🟢 worka, SARIF в Security tab |
| E2E (browser) | Playwright скелет в user-ui · 0 сценариев | 🔴 не покрывает ни одну critical user-flow |
| Compliance | `admin_audit_log` table + `@AdminAudit` aspect | 🟢 captures, но не валидируется тестами |
| SLO monitoring | Prometheus + Grafana 5 dashboards | 🟡 нет alert routing, нет synthetic probes |
| DR drill | Runbook `DR-FAILOVER-DRILL.md` (G-35) | 🟢 написан, не выполнен ещё |
| Pentest | Не было | 🔴 G-33 Positive Technologies pending |

**Coverage итог:** unit good, integration thin, E2E missing, security automated-only-images.

---

## 2. Что больно прямо сейчас (top-5 testing pain)

| Боль | Доказательство | Бизнес-ущерб |
|------|----------------|--------------|
| **P0 — Зависит от ручной regression перед каждой ship** | Sprint 12 + Sprint 13 — coordinator делал manual UI sweep по 22-23 страницам per merge | Cycle time +2-4h каждый release; забываемые edge cases |
| **P1 — Pilot client может сломать swap flow в нашем blind spot** | `FullSwapFlowIT` mock'ает kyc + token-service; реальные cross-service races не проверены | Один bug в проде = pilot отказ + reputational |
| **P2 — Compliance audit нечем доказать** | Audit log есть, но никто не запускает "докажи мне что transaction X прошёл правильно", evidence pack отсутствует | 115-ФЗ audit fail → штраф / лицензионный риск |
| **P3 — Performance regression не отлавливается** | k6 не в CI; baseline в README ручной, никто не сравнивает | Внезапное падение p99 в проде → SLA breach |
| **P4 — Security регрессии только Trivy-image-level** | Нет OWASP ZAP, нет SAST для Java/TS, нет DAST | Sprint 16+ pentest найдёт что-то базовое → embarrassment |

---

## 3. Sprint-by-sprint plan (Q3-Q4 2026)

### Sprint 13 (2 недели) — Закрыть P0 ручной regression

| ID | Investment | Effort | Owner | ROI |
|----|-----------|--------|-------|-----|
| **TI-1** | **E2E Playwright happy-path** — login → /pools → /pools/<id>/liquidity add → /positions claim → /swap → logout. 1 spec, 6-step flow. Запуск в CI `frontend.yml` matrix. | M (3d) | FE | Замена ручного sweep на 3 минуты CI |
| **TI-2** | **JaCoCo + Sonar setup** — coverage report on every PR, baseline tracked, ratchet (no decrease allowed). Применяется к dlmm-common + dlmm-pool-engine + dlmm-user-service (priority modules). | S (1d) | BE | Видим белые пятна без чтения кода |
| **TI-3** | **k6 в CI nightly** — gateway scrape, run baseline.js with 100 VUs (не full 300), compare p99 against `loadtest/baseline.thresholds.json`. Fail if p99 regression > 30%. | M (2d) | SRE | Early warning для perf regression |
| **TI-4** | **UAT scripts** for Анна / Дмитрий / Алексей personas в `docs/uat/` — 3 markdown файла, по 10-15 шагов каждый, картинки. Pilot client runs them once per release. | S (1.5d) | PO + tech writer | Pilot говорит "пройди script", не "сами проверьте" |
| **TI-5** | **Pre-prod smoke runner** — `scripts/smoke.sh` (extension of existing ultrareview-smoke.sh): 30+ assertions, json output, exit code → ops pipeline. | S (1d) | SRE | 60-sec validation после deploy |

**Sprint 13 итого:** 8.5 days = ~1 FE + 0.5 BE + 0.5 SRE. **Output:** автоматизированная regression confidence.

---

### Sprint 14 (2 недели) — Compliance + security maturity

| ID | Investment | Effort | Owner | ROI |
|----|-----------|--------|-------|-----|
| **TI-6** | **Compliance audit evidence framework** — endpoint `GET /api/v1/admin/compliance/evidence?txId=...&period=...` returns full forensic trail (audit_log + outbox_events + balance_mutations) для arbitrary transaction. Бизнес: "ЦБ запросил Tx X — выдать за 5 мин не за 5 дней". | M (3d) | BE | 115-ФЗ readiness |
| **TI-7** | **Multi-user / multi-org integration tests** — Testcontainers: Org A user не видит Org B данные, RBAC enforcement (OWNER/TRADER/VIEWER), audit log captures user-role correctly. 5 scenarios. | M (3d) | BE | G-21 protections не breakнутся регрессией |
| **TI-8** | **OWASP ZAP в CI** — weekly DAST scan staging, SARIF output → Security tab. Алерт для new High/Critical findings. | M (2d) | SRE | Catches XSS, CSRF, IDOR в ваше отсутствие |
| **TI-9** | **Snyk / Dependency-Check** в CI — SAST для Java + TS зависимостей, fail PR на known CVE > High. | S (1d) | SRE | Stop CVE-bleeding в dependencies |
| **TI-10** | **Cross-browser Playwright matrix** — TI-1 спецификация запускается в Chrome + Firefox + WebKit. Catches AntD render quirks. | S (1d) | FE | Sber Online users use everything |

**Sprint 14 итого:** 10 days. **Output:** compliance evidence + automated security scanning + cross-browser regression.

---

### Sprint 15 (2 недели) — Chaos + synthetic + DR

| ID | Investment | Effort | Owner | ROI |
|----|-----------|--------|-------|-----|
| **TI-11** | **Chaos engineering** via Toxiproxy — automated test: stop token-service mid-swap → Circuit Breaker opens cleanly → no money lost → swap retried after recovery. 4 scenarios (db loss, kafka loss, redis loss, gateway timeout). | M (3d) | SRE + BE | Resilience4j не theoretical, доказано работает |
| **TI-12** | **Synthetic monitoring** k6-cloud probes (or self-hosted) — every 5 min: POST /auth/login + GET /pools + simulate-swap-then-cancel. Page если probe fails 3× in row. | M (2d) | SRE | Catches production outage до user complaint |
| **TI-13** | **Execute DR drill #1** per `DR-FAILOVER-DRILL.md` — first real run, calibrate SLO baselines, file follow-up tickets. | S (1d) | SRE on-call | Validates runbook не stale |
| **TI-14** | **Test data management** — anonymized dump generator для staging: scrub emails/phones/balance amounts but preserve shape and edge cases. Cron monthly refresh. | M (3d) | SRE + BE | Realistic staging — реальные edge cases |
| **TI-15** | **Mutation testing** (PITest) on `dlmm-common` + `dlmm-pool-engine` — find tests that pass even when production code is broken. Target 50%+ mutation coverage первые 6 месяцев. | S (1.5d) | BE | Quality-over-quantity for unit tests |

**Sprint 15 итого:** 10.5 days. **Output:** chaos-proof + synthetic SLO monitoring + executed DR drill + realistic test data.

---

### Sprint 16+ (regulatory readiness)

| ID | Investment | Effort | Owner | ROI |
|----|-----------|--------|-------|-----|
| **TI-16** | **AML/SAR test suite** — generate 50+ scenarios (wash trade, structuring, layering, sudden velocity change). Auto-validate detection engine catches each. F-17 prerequisite. | L (5d) | BE + Compliance | Pre-validate F-17 before deploy |
| **TI-17** | **KYC edge case battery** — 25 scenarios (expired doc, mismatched name, sanctions hit, retry-after-rejection, multi-citizenship). Mock IDP + JWT. | M (3d) | BE | Reduces 80% KYC support tickets |
| **TI-18** | **Trade surveillance fixtures** — historical fake trades replicating known abuse patterns (MOEX cases). F-18 detection engine validates each. | M (3d) | BE + Compliance | F-18 confidence |
| **TI-19** | **Positive Technologies pentest** — external engagement, full white-box assessment, remediation backlog. | external (~4 weeks) | Procurement + Security | G-33 deliverable; pre-sale must-have for enterprise |
| **TI-20** | **SLA monitoring matrix** — Prometheus alerts × SLO promise mapping. Каждое SLO имеет alert; каждый alert имеет on-call runbook. | M (2d) | SRE | Demonstrable SLA для enterprise contracts |

**Sprint 16+ итого:** ~16 days internal + external pentest. **Output:** regulatory + enterprise sales readiness.

---

## 4. KPIs to track (bottom-up metrics)

Это business-side метрики чтобы доказать что test-инвестиции не "теоретические":

| Metric | Today | Target Q4 2026 | Target H1 2027 |
|--------|-------|----------------|----------------|
| **Time from PR open → merge** | manual review ~hours | ≤ 2h (CI + 1 review) | ≤ 1h |
| **Mean time to detect production bug** | hours (user complaint) | ≤ 5 min (synthetic monitor) | ≤ 60 sec |
| **Mean time to recover (MTTR)** | not measured | ≤ 30 min staging proven | ≤ 15 min |
| **% of releases with hotfix in <7d** | unknown | ≤ 20% | ≤ 10% |
| **Backend code coverage** | not measured | ≥ 70% on critical modules | ≥ 80% |
| **E2E pass rate (last 30 days)** | 0 (no E2E) | ≥ 95% | ≥ 98% |
| **Security CVEs (High+ open)** | unknown | 0 untriaged > 7 days | 0 untriaged > 24h |
| **DR drill SLO compliance** | n/a | ≥ 80% drills pass SLO | 100% |
| **Pilot client incidents / month** | n/a | ≤ 2 | ≤ 0.5 |

---

## 5. Resource allocation summary

| Team | Sprint 13 | Sprint 14 | Sprint 15 | Sprint 16 (per item) |
|------|-----------|-----------|-----------|----------------------|
| **Backend** | 0.5d | 6d | 4.5d | 11d (TI-16/17/18) |
| **Frontend** | 4d | 1d | 0 | 0 |
| **SRE** | 3d | 3d | 6d | 2d |
| **PO + tech writer** | 1.5d | 0 | 0 | 0 |
| **Compliance** | 0 | 0 | 0 | 5d (TI-16/18) |
| **External (pentest)** | 0 | 0 | 0 | 4 weeks (TI-19) |

**Bottleneck:** Sprint 15 SRE — 6 days = 1 FTE × 1+ weeks. Plan to hire contract SRE OR pull Sprint 13 SRE forward.

---

## 6. Risk reduction (по `RISK-REGISTER.md` mapping)

Каждая TI-* инвестиция целится в конкретный риск из register:

| TI item | Reduces | Severity → after |
|---------|---------|-------------------|
| TI-1 E2E happy-path | R5 (regression risk) | 3 → 1 |
| TI-3 k6 in CI | R8 (perf regression) | 3 → 1 |
| TI-6 compliance evidence | R11 (audit fail) | 4 → 1 |
| TI-7 multi-user tests | R12 (data leak) | 4 → 2 |
| TI-8 OWASP ZAP | R7 (XSS/CSRF) | 3 → 1 |
| TI-11 chaos eng | R3 (cascade failure) | 3 → 1 |
| TI-12 synthetic monitor | R17 (silent outage) | 3 → 1 |
| TI-19 pentest | R15 (pentest finding) | 4 → 1 |

Cumulative effect: 8 high-severity risks → low-severity. Risk register score drops от 54 → 17 (target).

---

## 7. Quick wins (можно начать прямо сейчас, < 1 day each)

Если PO хочет видеть progress немедленно — эти 5 things можно ship в один день каждое:

1. **Wire JaCoCo** — add plugin to `pom.xml`, output `target/site/jacoco/index.html` per module, link from CI summary
2. **Smoke runner exit codes** — extend `scripts/ultrareview-smoke.sh` (already exists!) to return non-zero exit на любой fail, deploy pipeline parses
3. **k6 baseline thresholds file** — extract pinned numbers from `loadtest/README.md` → `loadtest/baseline.thresholds.json`, добавить `--threshold` flag в run
4. **Test inventory** — script that lists ALL test files + count, run weekly via Schedule actions, post to Slack #engineering
5. **Bug bash сценарий** — 1-page `docs/uat/bug-bash-template.md` для pilot client "30 минут попробуй сломать"

---

## 8. What we're explicitly NOT investing in (anti-goals)

- **100% coverage chase** — past 80% the ROI flattens; quality of tests > number
- **Manual scripted regression** — automate or skip; manual ages instantly
- **End-to-end-only testing** — too slow, too brittle, too coarse; pyramid still 70% unit
- **Performance microbenchmarks** (JMH) — premature; we don't have a perf-sensitive hot loop yet
- **Property-based testing** (jqwik) until baseline tests stable
- **Contract testing** (Pact) until cross-service ownership splits — currently one team

---

## 9. Decision points (need PO input)

1. **Should we hire a dedicated QA engineer?** Currently testing distributed across BE/FE/SRE. Dedicated QA at Sprint 15 would unlock TI-11/12/13/14 in parallel.
2. **Outsource pentest or in-house?** Positive Technologies estimate ~3M ₽; in-house team would be ~6M ₽/yr salary. Outsource recommended for one-shot G-33.
3. **Test data: anonymized prod dump or synthetic?** Anonymized = realistic but DPIA + legal review. Synthetic = no compliance risk but misses edge cases.
4. **SLA targets** — what's the SLO we promise enterprise clients? 99.5% / 99.9% / 99.95%? Drives investment in TI-11/12/13.

---

## 10. Backlog tagging convention

When tickets land in tracker, tag with:
- `test-investment` — this plan's TI items
- `risk-mitigation` — links back to RISK-REGISTER row
- `pilot-blocker` — required before pilot ship (TI-1, TI-2, TI-3, TI-4, TI-5)
- `enterprise-blocker` — required for first 50M ₽ enterprise contract (TI-6, TI-8, TI-19, TI-20)
- `regulatory-blocker` — required for 115-ФЗ / ЦБ filing (TI-6, TI-16, TI-19)

---

*Author: Engineering · 2026-05-26. Companion to RISK-REGISTER.md, SPRINT-PLAN.md, ROADMAP-LONGTERM. Next review: end of Sprint 13.*
