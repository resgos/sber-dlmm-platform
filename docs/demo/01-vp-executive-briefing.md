# VP / Executive briefing — Sber DLMM Platform

> **Audience:** VP / C-level (PO sponsor, CFO, CIO, Head of Treasury).
> **Goal:** show platform is delivery-ready, revenue tracking, low risk.
> **Duration:** 10 minutes + 5 Q&A. Slides optional; product walk-through is the deck.
> **Date:** 2026-05-22.
>
> **Open with:** *"За шесть спринтов мы прошли путь от прототипа до платформы, которая зарабатывает деньги. Сегодня покажу ключевые цифры, что внутри, и что мы делаем дальше."*

---

## 0. The one-slide summary (30 sec)

```
┌─────────────────────────────────────────────────────────────────┐
│  Sber DLMM Platform — Q2 2026 status                            │
├─────────────────────────────────────────────────────────────────┤
│  ✅  10 микросервисов + 2 SPA, Prod-grade backend на Java 21    │
│  ✅  254 автотеста, 5 CI-pipelines, dark mode + i18n EN         │
│  ✅  P0 5/5 · P1 15/18 · P2 17/17 · TD 7/10                     │
│  ✅  4 пилота: FX hedge (3 корп), Spasibo writeback (BU sandbox) │
│  💰  Runrate: 30M ₽/год → план Q4 1.5–2.0B ₽/год                │
│  🎯  Sprint 10 фокус: SLA-MM контракты, SberID SSO, Telegram bot │
└─────────────────────────────────────────────────────────────────┘
```

---

## 1. Что работает прямо сейчас (3 min — live click-through)

| Окно | Что показать |
|---|---|
| `/login` → `/` | Логин по email/password, дашборд с портфелем |
| `/swap` | Мгновенный обмен SRUB → SBER, расчёт quote, исполнение |
| `/hedge` | FX-хедж: «зафиксировать курс CNY на 100К ₽», quote, открыть позицию |
| `/positions` | LP-позиции: Health Score, P&L, диапазон бинов, алерты, авто-сбор |
| `/pools/compare?p=…` | Сравнить SRUB/SBER vs SRUB/GAZP бок-о-бок — победитель подсвечен |
| Admin → `/api-analytics` | Throttle ratio по тарифам в реальном времени |
| Admin → `/transactions/suspicious` | Подозрительные транзакции — wash-trade детектор |

**Skills to flex during the walk:**
- Тёмная тема — переключите в Profile → видно адаптацию hero/cards/Modal'ов
- RU/EN toggle в header — мгновенный переключатель
- Алерты по позициям — нажать «Тест» → AntD toast + (если разрешено) браузерное уведомление

---

## 2. Цифры, которые имеют значение (2 min)

### Sprint velocity

| Sprint | Закрыто P0 | P1 | P2 | TD | Кумул. revenue runrate |
|---|---:|---:|---:|---:|---:|
| 1 | 4/4 | — | — | 3 | 0 ₽/год |
| 2 | — | — | — | 4 | 0 ₽/год |
| 3 | — | 3/8 | — | — | 30M ₽/год (protocol fee live) |
| 4 | — | 3/8 | — | — | 50M ₽/год (+FX hedge) |
| 5–7 | — | 5/8 | — | — | 200–400M ₽/год |
| 8 (UX hardening) | 5/5 audit | 2/8 | 2/8 | 1 | 400M ₽/год |
| 9 (commercial) | — | — | 7/8 | — | 800M–1.1B ₽/год (план) |
| 9-DS-r4 + 10 waves 1-3 | 5/5 | 15/18 | **17/17** | 7/10 | ... |

### Качество кода

- **254 автотеста** (141 user-ui + 24 admin-ui + 96 backend)
- **5 CI pipelines**, все mandatory зелёные (backend, frontend, schema-drift, runbook-drift, container-scan)
- **Hex-ratchet baseline** 274 — заморожен, новые `#xxxxxx` блокируют PR
- **UI shared-code drift watchdog** — admin/user UIs не расходятся
- **0 high-severity рисков** в RISK-REGISTER

### Production-readiness gap

| | Готово | Sprint 10+ |
|---|---|---|
| Backend | 10 микросервисов, JWT, Resilience4j на 3 сервисах | Sleuth/Zipkin tracing, Workspace refactor |
| БД | Postgres + Liquibase, dev backup script | WAL-G + S3 + cross-region (TD-6 runbook готов) |
| Secrets | docker/.env + fail-fast YAML | Sber Vault интеграция (TD-2 runbook готов) |
| Deploy | docker-compose | Helm chart (TD-5) |

---

## 3. Revenue trajectory (2 min)

```
            план fact
2026 Q3   |█████░░ 800M  ← наш сегодняшний потолок при текущей загрузке
2026 Q4   |████████░░ 1.5–2.0B  ← SLA-MM контракты × 3 + SberID SSO
2027 Q1   |██████████ 2.2–2.8B  ← AML SAR moat + tax export
2027 Q2+  |██████████████ 3.5–4.5B  ← multi-sig + DLMM SDK + bonds
```

**Что разгоняет рост:**
1. **F-13 SLA market-maker контракты** (score 12.5 в new-features) — каждый по 50M ₽/год; 3 контракта = +150M к Q4
2. **F-25 SberID SSO** — 100M пользователей Sber Online; даже 0.1% конверсия = 100K новых клиентов
3. **AML SAR авто-подача** в Росфинмониторинг — moat который никто, кроме Sber, не построит за разумное время

---

## 4. Что мне нужно от VP (2 min — the ask)

| Решение | Срок | Импакт |
|---|---|---|
| **Sponsor для Sber Treasury** как primary LP (M#2 пилот) | до конца Q3 | +100M ₽/год TVL, разгоняет fee revenue по top-3 пулам |
| **Sber Online BU контракт** на SberID интеграцию (F-25) | Sprint 10 kickoff | Retail acquisition multiplier — главный driver Q4 uplift |
| **Compliance signature** на 5.D ЦФА memo | до Sprint 5 mid | Разблокирует 161-ФЗ работу: СБП-rail, ЦБ реестр финплатформ |
| **Marketing budget Q4** на ребрендинг user-side под Сбер Нова | Sprint 11 | Готовность к публичному запуску |

---

## 5. Risks I'm watching (1 min)

| Risk | Severity | Mitigation |
|---|---|---|
| R#20 — Same-pool row-lock contention (production scale) | Med | Optimistic-lock landed Sprint 4 #4.7; k6 baseline шла Sprint 7; ещё круг нагрузочного тестинга Sprint 10 |
| R#33 — admin-bff WebClient circuit-breakers | Low | 3 из 6 закрыты; оставшиеся 3 в Sprint 10 |
| TD-1 — Liquibase init-db split | Low | Тактический preConditions hack держит; полный split не нужен пока schema стабильна |
| **External**: Pangolin не доступен с GitHub runners | Low | continue-on-error advisory; CI публикует pangolin matrix leg как evidence для Минцифры реестр (Sprint 6 #6.10) |

---

## 6. Q&A bank

**Q: Сколько стоит платформа в эксплуатации (run-rate)?**
A: 12 контейнеров в compose; production Helm chart (Sprint 10) — оценочно 3 ноды × 32GB = ~120K ₽/мес на Sber Cloud + 25K на Postgres managed = ~150K ₽/мес. С учётом 800M–2B run-rate revenue, gross margin > 99% после ramp-up.

**Q: Что мы делаем если Sber Treasury не идёт в пилот?**
A: Fallback на М#3 FX-хедж пилот (3 корп клиента в pipeline) + sponsored pool placement (M#12). Сократит Q4 forecast с 1.5–2.0B до 1.0–1.4B, но не сломает план.

**Q: Какая компетенция у команды на DLMM-математику?**
A: `LbDlmmMath` — наш референс-имплементейшн по Trader Joe LB whitepaper §3.1. Покрыт unit-тестами, canonical bin-pricing формулы пинятся `BinPriceAtBinTests`. Senior backend (lead) — экс-разработчик MOEX матчинг-движка.

**Q: Tinkoff / VTB могут это повторить?**
A: Технически — да, за 9–12 месяцев. Наши moats: (1) **Sber distribution** — Sber Online 100M users, SberSpasibo 60M; (2) **regulatory ground floor** — мы уже работаем в "internal clearing" frame и готовим ЦФА memo; (3) **time-to-market** — 6 месяцев lead до Q1 2027 их вероятного релиза.

**Q: Что если ЦБ опубликует регулирование DEX/DLMM?**
A: Сценарий "регулирование пришло" — мы единственная платформа, готовая к compliance battery (Sprint 12 AML SAR auto-file). Сценарий "запрет" — переключаемся на pure-internal Sber Treasury LP, теряем retail но сохраняем B2B.

---

## 7. What I'll deliver before next VP touch-point

- ✅ Sprint 10 wave 1 done (F-07 rebalancer, F-22 runbook generator)
- ✅ Sprint 10 wave 2 done (F-15 API analytics, F-21 KYC re-verify, N-03 health score, N-04 auto-claim)
- ✅ Sprint 10 wave 3 done (dark-theme polish, first-impression review fixes)
- 🔄 Sprint 10 wave 4 (next 2 weeks): F-01 Telegram bot MVP, F-13 SLA contract paper, F-25 SberID SSO design

**Ready when you are.**

— Sber DLMM Platform team
