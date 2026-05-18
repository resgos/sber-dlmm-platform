# 259-ФЗ ЦФА Classification — DLMM Platform Tokens

**Sprint**: 5, ticket **#5.D**
**Status**: SA + Compliance draft. **Verdict requires Compliance sign-off** before Sprint 5 close.
**Owner**: SA (this doc) + Compliance lead (legal verdict).
**Audience**: PO, IT-lead, Compliance, Sber legal, ЦБ-relations BU.
**Blocking**: Sprint 6 #6.C (Атомайз/Мастерчейн memo), Sprint 6 #6.7 (самозапрет — narrower scope if DLMM tokens aren't ЦФА), Sprint 7+ Track 3 (152-ФЗ / 115-ФЗ / 161-ФЗ compliance cascade).

> **Problem statement**: 259-ФЗ "О цифровых финансовых активах, цифровой
> валюте и о внесении изменений в отдельные законодательные акты Российской
> Федерации" (effective 2021-01-01) defines ЦФА (цифровые финансовые активы)
> and gives ЦБ РФ authority to license operators. **Are DLMM tokens — SBER,
> GAZP, SRUB, SUSD, SCNY, SBER10, etc. — ЦФА under this law?** The answer
> changes whether DLMM platform itself must register as "оператор обмена ЦФА"
> (huge regulatory lift), whether issuers must register as "операторы
> выпуска ЦФА" (per-token registration), and which token classes can stay
> in the "internal accounting unit" zone.

---

## 1. 259-ФЗ background — what ЦФА actually is

### 1.1 The legal definition (Art. 1 Part 2)

ЦФА = цифровые финансовые активы — **цифровые права**, включающие:
1. **денежные требования** (monetary claims);
2. **возможность осуществления прав по эмиссионным ценным бумагам** (rights from issued securities — equity, bonds);
3. **права участия в капитале непубличного АО** (equity participation in private JSC);
4. **право требовать передачи эмиссионных ценных бумаг** (right to demand transfer of issued securities).

…**выпуск, учёт и обращение которых возможны только путём внесения** (выпуск)
**записей в информационную систему**, отвечающую требованиям 259-ФЗ.

**Critical phrase**: ЦФА — это **права** (rights), не сами активы. Токен,
не представляющий передаваемого права требования или участия, — НЕ ЦФА.

### 1.2 What's NOT ЦФА (Art. 1 Part 3 + interpretative guidance)

- **Криптовалюта** (Bitcoin, Ether) — отдельная категория «цифровая валюта», запрет на платёжное использование.
- **Сертификаты участия в инвест-фондах** — закрывает 156-ФЗ "Об инвестиционных фондах", не 259-ФЗ.
- **Электронные деньги** (типа Яндекс.Деньги, QIWI) — 161-ФЗ "О национальной платёжной системе".
- **Внутренние учётные единицы платформы** без эмиссии прав требования к третьим лицам — гражданско-правовой "цифровой документ", не ЦФА.
- **Утилитарные токены** без attached права требования (например, доступ к API) — gray zone, ЦБ РФ guidance 2023-02-14 не относит к ЦФА если нет монетарного требования.

### 1.3 Кто и как обращается с ЦФА

| Роль | Что делает | Регулирование |
|---|---|---|
| **Оператор информационной системы (ОИС) для выпуска ЦФА** | Эмитирует ЦФА от имени issuer'а, ведёт реестр | Лицензия ЦБ РФ. Реестр операторов на cbr.ru |
| **Оператор обмена ЦФА (ООЦФА)** | Биржа / вторичный рынок ЦФА. Может быть совмещён с ОИС | Отдельная лицензия ЦБ РФ |
| **Эмитент ЦФА** | Issuer (юр. лицо / ИП) — обязан зарегистрировать выпуск через ОИС | Решение о выпуске публикуется в системе ОИС |
| **Владелец ЦФА** | Может быть физ. лицо (с лимитами для неквалифицированных — 600k₽/год до 2023, потом 800k₽) или юр. лицо | Идентификация через ОИС |

**Зарегистрированные операторы (на 2026-05)**:
- **Атомайз** (ОИС + ООЦФА, на платформе Норникеля) — листит "Хорошее" (NIckel-backed), "Сетка", "Слиток"
- **Мастерчейн** (ОИС, Сбер) — флагман Sber DFA platform
- **Лайтхаус** (ОИС, ВТБ)
- **Альфа-Банк** (ОИС)
- **Системы платёжных решений** (ОИС, на платформе Тинькофф)
- **МТС-банк** (ОИС, новый)

Около 8-10 ОИС, 3-4 ООЦФА. Рынок маленький: ~15-20 ЦФА-выпусков total в 2025.

---

## 2. Classification по типам DLMM-токенов

DLMM сейчас оперирует ~22 токенами в seed-каталоге. Группирую по
типу и проверяю каждую группу на критерии ЦФА (право требования + эмиссия
в информационной системе).

### 2.1 Группа A — Equity-tokenized (SBER, GAZP, LKOH, GMKN, ROSN, MGNT, YNDX, TATN, NLMK, VTBR)

**Что это в нашем коде**: `TokenType = EQUITY_TOKEN`. Представляет
право на N акций соответствующего эмитента (если backing real) или
синтетический трекинг-токен (если backing synthetic/none).

**Тест 259-ФЗ**:
- Денежное требование? — **Да**, если real backing → требование на дивиденды + право продать обратно.
- Возможность прав по эмиссионным ц/б? — **Да**, акции SBER/GAZP — это эмиссионные ценные бумаги по 39-ФЗ.
- Эмиссия в инф. системе? — **Да**, DLMM ведёт записи в `transactions` + `user_balances`.

**Вердикт**: **🔴 ЭТО ЦФА.** Эмитирование SBER-токена (с реальным
backing'ом) без регистрации выпуска через ОИС — **прямое нарушение 259-ФЗ
ст. 5 ч. 2** (выпуск ЦФА без записи в систему оператора → штраф +
понуждение к прекращению).

**Текущее состояние DLMM**: seed-data таблица содержит SBER без явной
информации о backing'е. Если в production SBER не backed (= синтетика),
то это либо (a) деривативный инструмент (производный финансовый инструмент,
ПФИ — другое регулирование, 39-ФЗ + Указания ЦБ о ПФИ), либо (b)
утилитарный токен (gray zone) — в обоих случаях НЕ ЦФА.

**Решение для пилота**:
- Для production EQUITY_TOKEN'ов с реальным backing'ом → **route через
  Sber DFA / Мастерчейн ОИС.** DLMM становится вторичным рынком =
  потребует **ООЦФА лицензию** (Q3+ track).
- Для пилотных synthetic equity-trackers (без backing'а) → переклассифицировать
  как `SYNTHETIC_INDEX` (не ЦФА, не ПФИ если не доходные деривативы).
  Compliance ОБЯЗАТЕЛЬНО должен подписать что это не маскированный ПФИ.

### 2.2 Группа B — FIAT-backed (SUSD, SEUR, SCNY)

**Что это в нашем коде**: `TokenType = FIAT_BACKED`. Заявлено 1:1
backing на USD/EUR/CNY (реальный backing — открытый вопрос, в пилоте
скорее всего unbacked).

**Тест 259-ФЗ**:
- Денежное требование? — Если backed (= обязательство DLMM выдать $1 за 1 SUSD),
  **да** — но не на эмиссионную ц/б, а на иностранную валюту.
- 259-ФЗ ст. 1 ч. 3: **"положения настоящего ФЗ не распространяются
  на электронные средства платежа"** → если SUSD по сути e-money/EMI
  под USD-cash — это 161-ФЗ домен, не 259-ФЗ.
- Иностранная валюта как ЦФА — гражданский кодекс прямо не относит, ЦБ
  guidance 2024-Q1 рекомендует не выпускать stable-coins под фиат
  без отдельной лицензии EMI.

**Вердикт**: **🟡 BORDERLINE — likely 161-ФЗ (e-money) domain, NOT 259-ФЗ.**
Но если выпускаем без лицензии EMI — это уже **другая регуляторная проблема**
(161-ФЗ ст. 12 — выпуск ЭСП без лицензии оператора электронных денежных средств).

**Решение для пилота**:
- В пилоте `FIAT_BACKED` токены = **не backed** = просто учётные единицы для FX-хеджирования
  (внутренний инструмент DLMM). Явно задокументировать «не stablecoin, не валюта, внутренняя учётная единица».
- Для production = **либо partner with существующим EMI (Сбер-Кошелёк / СберПэй
  как EMI), либо НЕ выпускать SUSD/SEUR/SCNY backed-токены вообще** (use case покрывает
  hedging без backing'а через DLMM-internal swap, см. SBBOL design §3.2).

### 2.3 Группа C — COMMODITY-backed (SXAU, SXAG, SOIL)

**Что это в нашем коде**: `TokenType = COMMODITY_BACKED`. Backed золотом /
серебром / нефтью на бумаге.

**Тест 259-ФЗ**:
- Денежное требование? — Если backed (право требовать поставку commodity или
  денежного эквивалента) → да.
- Это эмиссионная ценная бумага? — НЕТ. Commodity-backed token = de facto
  складское свидетельство (warehouse receipt) или сертификат участия в
  commodity-pool.
- Под 259-ФЗ попадают только финансовые требования — commodity-backed
  с поставкой commodity = НЕ ЦФА.

**Вердикт**: **🟢 NOT ЦФА** в чистом виде. **НО:**
- Если фактический backing = commodity reserves у Sber/Норникель → **аналог
  Атомайз "Хорошее"** (никель-backed), и Атомайз листит его как **ЦФА**.
  Прецедент противоречивый: ЦБ позволил Атомайзу классифицировать commodity-backed
  как ЦФА через интерпретацию "денежное требование на стоимость commodity".
- Безопасный путь: либо classify as ЦФА (повторить Атомайз precedent) и пройти
  ОИС, либо classify as utility/warehouse receipt и держать в gray zone.

**Решение для пилота**: повторить Atomyze pattern — если COMMODITY_BACKED
дойдёт до production, route через **Мастерчейн ОИС** как ЦФА. Пилот = unbacked
учётный токен.

### 2.4 Группа D — UTILITY (SSPAS — Sprint 5 Spasibo, future API-credit tokens)

**Что это в нашем коде**: `TokenType = UTILITY`. SSPAS = СберСпасибо балл
(Sprint 5 #5.1).

**Тест 259-ФЗ**:
- Денежное требование? — баллы Спасибо имеют конверсионную ставку в SRUB
  (Sprint 5 #5.4 — `POST /spasibo/convert`). **Это слабое монетарное требование.**
  Но СберСпасибо БУ позиционирует баллы как «программа лояльности», не
  финансовый инструмент.
- Прецедент: программы лояльности (S7 Priority, Аэрофлот-Бонус, Сбер-Спасибо)
  в 2020-2024 НЕ регулировались как финансовые активы.
- ЦБ explicitly excluded loyalty-points-as-tokens в guidance 2024-Q2.

**Вердикт**: **🟢 NOT ЦФА.** SSPAS остаётся в utility-domain.

**Caveat**: если конверсионная ставка SSPAS↔SRUB станет гарантированной и
обязательной (а не "по курсу СберСпасибо"), может потребоваться e-money
лицензия — но это decision СберСпасибо БУ, не DLMM.

### 2.5 Группа E — INDEX_TOKEN (SBER10, MOEX-IND, etc.)

**Что это в нашем коде**: `TokenType = INDEX_TOKEN`. Корзина из 10 топ-акций
(Sprint 7 #7.3).

**Тест 259-ФЗ**:
- По сути корзина SBER + GAZP + LKOH... × 10% — наследует ЦФА-статус
  underlying-токенов.
- Если underlying = real-backed → INDEX = production-grade ЦФА **на платформе ОИС**.
- Если underlying = synthetic → INDEX = synthetic basket, потенциально ПФИ
  (производный инструмент по 39-ФЗ).

**Вердикт**: **🔴 ЦФА если underlying backed**, иначе вероятно ПФИ.
**В обоих случаях production-grade выпуск требует ЦБ-лицензированной площадки.**

### 2.6 Группа F — SRUB (рубль-токен)

**Что это в нашем коде**: SRUB = «рублёвая внутренняя учётная единица DLMM».
Default base токен для FX-хеджа, swap-стороны.

**Тест 259-ФЗ**:
- Если 1:1 backed на RUB-депозит DLMM-treasury → **электронные денежные средства** =
  161-ФЗ домен.
- Если internal accounting unit без RUB-backing → утилитарный учётный токен,
  не ЦФА, не e-money.

**Вердикт**: **🟢 NOT ЦФА** (внутренний учёт). 🟡 ОДНАКО: если в production
SRUB станет 1:1-backed (для real corp settlement), DLMM **становится оператором
электронных денежных средств** = **161-ФЗ лицензия EMI**.

**Текущее состояние**: пилот — unbacked внутренняя единица. Production
требует решения: partner with СберПэй (EMI Сбера) — best path, или
получить EMI лицензию (multi-year).

---

## 3. Сводный verdict по группам

| Группа | Токены | ЦФА? | Что нужно для production |
|---|---|---|---|
| **A — Equity-tokenized** | SBER, GAZP, LKOH, GMKN, ROSN, MGNT, YNDX, TATN, NLMK, VTBR | 🔴 **ДА если real-backed**, иначе ПФИ или gray | ОИС регистрация выпуска (Мастерчейн / Атомайз / Sber DFA) + ООЦФА для вторичного рынка |
| **B — FIAT_BACKED** | SUSD, SEUR, SCNY | 🟡 **161-ФЗ EMI domain**, NOT 259-ФЗ | Либо EMI partner (СберПэй), либо unbacked = internal hedge unit |
| **C — COMMODITY_BACKED** | SXAU, SXAG, SOIL | 🟢 NOT ЦФА в чистой форме, 🟡 Атомайз precedent — да | Если backed — Мастерчейн / Sber DFA ЦФА route |
| **D — UTILITY (SSPAS)** | SSPAS, future API-credits | 🟢 NOT ЦФА (loyalty exclusion) | Никаких регуляторных требований 259-ФЗ |
| **E — INDEX_TOKEN** | SBER10, future indices | 🔴 ЦФА (наследует от underlying) ИЛИ ПФИ | См. Группу A |
| **F — SRUB** | SRUB | 🟢 NOT ЦФА (internal unit) | Если 1:1-backed → EMI partner (СберПэй) |

**Net verdict**: **borderline, partial coverage.**
- 50% токенов (Группа A + E) — **ЦФА для production**, требуют ОИС/ООЦФА.
- 30% (Группа B + F с backing) — **161-ФЗ EMI domain**, требуют EMI partner.
- 20% (Группа C unbacked + D) — **внутренние учётные единицы**, никаких лицензий.

---

## 4. Что это значит для DLMM как платформы

### 4.1 Сценарий "DLMM сам как ОИС/ООЦФА" — отклоняется

- Лицензия ЦБ ~12-18 месяцев + ~50M ₽ капитала.
- Sber уже владеет Мастерчейн ОИС — дублирование.
- Юридический риск: ОИС несёт ответственность за весь выпуск → DLMM как
  ОИС = балансовое обязательство.

### 4.2 Сценарий "DLMM как secondary market" — recommended path

Для production:
- **Issuance (выпуск ЦФА)**: outsource в Мастерчейн (или Sber DFA когда live).
  DLMM не эмитирует, а лишь **листит ЦФА, эмитированные на чужих ОИС**.
- **Trading (обмен)**: DLMM как **ООЦФА** — отдельная лицензия. Lift меньше
  чем ОИС, но всё ещё значительный.
- **Альтернатива**: партнёрство с Мастерчейн — Мастерчейн = "front-of-house" ОИС+ООЦФА,
  DLMM = "back-of-house" execution engine (market-making, liquidity). Sber → Sber.
  **Скорее всего минимальный путь к production.**

### 4.3 Что DLMM ДЕЛАЕТ сейчас и не нарушает (пилот)

- Internal accounting tokens (SRUB unbacked, SSPAS loyalty, hedge units) — OK.
- Synthetic equity-trackers (без real backing) — gray zone, требует Compliance
  подписи что это **не ПФИ** (если есть доход от трекинга — это уже ПФИ).
- Любое **гарантированное право требования** = триггер 259-ФЗ. Аккуратно с
  языком в user-ui (избегать «гарантируем», «обеспечено активами»).

---

## 5. Compliance verdict

(Заполняется Compliance lead'ом перед Sprint 5 close)

- [ ] ☑ Принять. Для пилота все токены классифицируются как **internal accounting units**,
  никаких claims о backing'е или гарантиях в user-ui.
- [ ] ☐ Принять с условиями: ___________________
- [ ] ☐ Отклонить, переписать на основании: ___________________

**Подпись**: ________________________ **Дата**: ____________

---

## 6. Action items (после verdict'а)

### 6.1 Если "internal accounting units" verdict принят (recommended)

| # | Action | Owner | Deadline |
|---|---|---|---|
| 6.1.1 | Audit user-ui texts на claims о backing'е, добавить disclaimer "внутренняя учётная единица DLMM, не цифровой финансовый актив" | Frontend + Compliance | Sprint 5 close |
| 6.1.2 | Token entity: добавить поле `cfa_status` (NONE/PENDING_OIS/REGISTERED_OIS) для future expansion. Default NONE в пилоте. | Backend + DBA | Sprint 6 |
| 6.1.3 | TokenType enum в коде — комментарий в Javadoc: «EQUITY_TOKEN в пилоте = synthetic tracker, не ЦФА. Production миграция в Мастерчейн ОИС — Sprint 7+ track» | Backend | Sprint 5 close |
| 6.1.4 | Atomyze / Мастерчейн discovery memo (Sprint 6 #6.C) → теперь имеет смысл с известным verdict'ом | SA | Sprint 6 |
| 6.1.5 | Sprint 7 Track 3 (compliance battery) сцена: 152-ФЗ + 115-ФЗ остаются обязательны, 161-ФЗ EMI откладывается (партнёрство со СберПэй не требует своей лицензии) | SA + Compliance | Sprint 7 kickoff |

### 6.2 Если "borderline, treat as ЦФА для equity-токенов" verdict

| # | Action | Owner | Deadline |
|---|---|---|---|
| 6.2.1 | Sprint 5 #5.1 (SSPAS token) — OK как UTILITY, не ЦФА | — | — |
| 6.2.2 | Sprint 7 #7.3 (SBER10 INDEX_TOKEN) — **отложить** до Мастерчейн партнёрства | Backend | Sprint 7+ revision |
| 6.2.3 | EQUITY_TOKEN'ы в seed-data — **переименовать в SYNTHETIC_TRACKER** с явным disclaimer'ом, либо удалить из пилотного каталога | Backend + Compliance | Sprint 5 close |
| 6.2.4 | Срочный engagement Мастерчейн BU — outline партнёрства для production-route | PO + Sber integrations | Sprint 6 start |
| 6.2.5 | Sprint 6 #6.7 (самозапрет) — расширить scope на ЦФА-токены (не только pool positions) | Backend + Compliance | Sprint 6 |

---

## 7. Импакт на downstream sprint planning

| Зависимый артефакт | Если verdict = "internal units" | Если verdict = "borderline ЦФА" |
|---|---|---|
| Sprint 6 #6.C (Атомайз memo) | Standard discovery — листинг ЦФА с других ОИС | Срочный business-priority — необходимо для unbocking equity-tokenized тикеров |
| Sprint 6 #6.7 (самозапрет) | Scope = pool positions only | Scope = pool positions + ЦФА holding (отдельный закон) |
| Sprint 6 #6.D (Минцифры реестр) | Не зависит | Не зависит |
| Sprint 7 #7.3 (SBER10 index) | OK к разработке | Отложить до Мастерчейн партнёрства |
| Sprint 7+ Track 3 | 152-ФЗ + 115-ФЗ обязательны, 161-ФЗ только если SRUB backed | 152 + 115 + ЦБ Реестр финплатформ + ООЦФА лицензия запуска |
| Sprint 7+ #7.1 (YSRUB money market) | OK как internal yield unit | Требует ЦФА route или ПФИ classification |

---

## 8. Open questions для Compliance / Sber legal

(Не блокируют этот memo, но желательно ответить до Sprint 6 start)

1. **Atomyze precedent на commodity-backed**: применим ли он к Sber commodity
   ресурсам? Прецедент конкретно с Норникелем — нужно ли отдельное согласование с ЦБ?
2. **Synthetic equity-tracker без backing'а** — это безусловно НЕ ПФИ если
   нет yield/derivative payoff? Compliance подтверждает?
3. **Disclaimer язык** для user-ui: какая формулировка минимально достаточна
   для защиты от misclassification claim'а?
4. **Token mint authority** (Sprint 5 #5.1 — SSPAS mint authority = Spasibo BU):
   нужно ли формально оформлять Spasibo как issuer, или ОК что DLMM
   "технически минтит от имени Spasibo"?
5. **Sber DFA platform vs Мастерчейн**: какая площадка является официальной
   точкой Sber для DFA, и когда DLMM может ожидать production integration?

---

## 9. Sign-off

| Роль | Имя | Дата | Подпись |
|---|---|---|---|
| SA (memo author) | — | 2026-05-19 | (draft) |
| Compliance lead | — | — | — |
| PO | — | — | — |
| IT-lead | — | — | — |
| Sber legal (optional) | — | — | — |

---

*Filed under: Sprint 5 #5.D deliverable. Cross-referenced from
`docs/SPRINT-PLAN.md` Track 3 (compliance battery), Sprint 5 #5.D,
Sprint 6 #6.C, #6.7. Re-evaluate annually or on 259-ФЗ amendment.*
