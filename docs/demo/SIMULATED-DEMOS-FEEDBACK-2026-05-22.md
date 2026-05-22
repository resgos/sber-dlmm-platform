# Simulated demo run-through + growth-points backlog

> Provoked transcripts of all 5 demos с симулированными honest
> реакциями + жёсткими вопросами от каждой персоны + synthesis в
> приоритизированный backlog роста платформы.
>
> Дата: 2026-05-22. Source scenarios — `docs/demo/01..05-*.md`.
> Tone: реалистичные клиентские возражения, не softball.

---

## Содержание

1. [VP demo (10 мин)](#1-vp-demo-10-мин)
2. [Investor pitch (20 мин)](#2-investor-pitch-20-мин)
3. [ИП Анна, малый бизнес (5 мин)](#3-ип-анна-малый-бизнес-5-мин)
4. [Финдир Дмитрий, средний бизнес (10 мин)](#4-финдир-дмитрий-средний-бизнес-10-мин)
5. [Александр Викторович, enterprise (15 мин)](#5-александр-викторович-enterprise-15-мин)
6. [**Synthesis — growth-points backlog**](#6-synthesis-growth-points-backlog)
7. [**Sprint allocation**](#7-sprint-allocation)

---

## 1. VP demo (10 мин)

**Audience:** Елена Сергеевна, VP Treasury Sber Group. Прагматик, 18 лет в банке, видела 4 неудачных RuCBDC-пилота, скептична к "platform" rhetoric.

### Открытие

> **Лид:** *"За шесть спринтов мы прошли путь от прототипа до платформы, которая зарабатывает деньги..."*
>
> **Елена:** *(перебивает)* — Сколько зарабатывает прямо сейчас, не "к Q4"?
>
> **Лид:** Run-rate 30М ₽/год по protocol fee на трёх флагманских пулах. Это live.
>
> **Елена:** *Что это значит — "live"? Реальные клиенты или Sber Treasury seed?*
>
> **Лид:** Seed-LP от Sber Treasury (через дочку M&M Asset) + 3 corp пилота FX-хеджа. До конца Q3 первый retail-пилот через ограниченный invite-list ~200 пользователей.
>
> **Елена:** Запомнила: 30М — *seed*, не настоящий PMF. Дальше.

### Walk-through

> *(walk через 7 экранов как в скрипте)*

**Реакция по ходу:**

| Экран | Что зацепило Елену |
|---|---|
| `/swap` quote | *"Покажите, что произойдёт, если связь оборвётся между quote и execute. Я видела платежные системы, где quote ушёл — деньги списались, факт исполнения не вернулся."* |
| `/positions` Health Score | *"Это что мне говорит конкретно? У меня 'Здоровье 47'. Что делать? У вас есть кнопка 'исправить'?"* |
| `/pools/compare` share link | *"Если я скину эту ссылку коллеге, у которого аккаунта нет — что он увидит? Полную аналитику пулов? Это leak коммерческой информации."* |
| Admin → `/api-analytics` | *"А кто из retail-клиентов сейчас на Free тарифе? Я хочу видеть имена топ-10 throttle'нутых клиентов чтобы сейлзы могли пойти их апселлить."* |
| `/transactions/suspicious` | *"Эти 12 пометок 'на ревью' — кем создавались? Какой response time у вашего compliance officer'а?"* |

### Финал — the ask

> **Лид:** *"Что мне нужно от вас — sponsor для Sber Treasury как primary LP, Sber Online BU контракт на SSO..."*
>
> **Елена:** Sber Online BU — это Куликов. Он у меня в подчинении, я знаю что он скажет: "приходите когда у вас будут конкретные cohort-метрики, не just runrate". У вас есть DAU/MAU/retention?
>
> **Лид:** ...Не публикуем — мы в pilot, retention metric pool depth пока недостаточен.
>
> **Елена:** Ну вот. Когда у вас будет 30-day retention на second cohort retail — приходите. До тех пор — рассказывать VP вторую квартал подряд про "1.5B Q4" мне неприятно.

### Что Елена сказала бы коллегам после встречи

> *"Платформа выглядит профессионально, не embarrassment. Но runrate в 30М — это `проектная` цифра, а не `продуктовая`. Я не могу нести Sergei Sergeyevich план на 1.5B без живой когорты пользователей. Дайте им ещё 2 спринта, пусть запустят retail invite-list и принесут когортную таблицу. Тогда обсудим SLA-MM бюджеты."*

### Извлечённые growth points (VP-driven)

1. **G-01** Cohort analytics dashboard (DAU/MAU/30d retention/D7 churn) — основа любой VP/board conversation
2. **G-02** Quote-execute idempotency story — explicit "what happens if connection drops between quote and execute" документация + automated test
3. **G-03** Health Score "что делать" recommendation — turn информацию в action (например, кнопка "ребалансировать эту позицию" right из Tooltip'а)
4. **G-04** Pool comparator share-link — public vs authenticated view; для public — ограничить только базовыми polled-data, без TVL drill-down
5. **G-05** Admin "Top throttled API clients" в `/api-analytics` — sales leads из rate-limit data
6. **G-06** Compliance review-queue SLA tracking + responder identity на каждой пометке

---

## 2. Investor pitch (20 мин)

**Audience:** Михаил, partner в Sber-affiliated strategic VC fund (внутренний, не публичный). 12 лет в Sber Group М&А, видел 30+ pitch'ей, sceptical-by-default.

### Открытие

> **Лид:** *"...мы зарабатываем на четырёх взаимоусиливающих потоках... траектория 3.5–4.5B ₽/год к Q3 2027..."*
>
> **Михаил:** *(не комментирует, листает страницу TAM)*. — TAM 77–94B ₽/год — откуда top-down эти числа?
>
> **Лид:** ЦБ РФ отчёты по NSP volume, Sber Online MAU официальная цифра.
>
> **Михаил:** Это **gross** оборот. Из 8–12 трлн ₽ в год корпоративных расчётов какой % реально будет gravitate к DLMM rail вместо SBP, а из gravitating — какой пойдёт через Sber? Сделайте bottom-up по 3 сегментам с предположениями. Top-down я не верю.

### Quizzing on moats

> **Михаил:** Slide "Moat 1 — Sber distribution". Sber Online как канал — это **renting attention**, не **owning customer relationship**. Что если завтра Sber Online BU поднимет referral fee на интеграцию до 30%?
>
> **Лид:** Sber Group internal pricing не работает по market model — это transfer pricing, регулируется Sber legal entity.
>
> **Михаил:** Это сейчас. Через 5 лет, когда вы хотите spin-out или раунд с external LP — Sber Group отделит affiliate fees, тогда unit economics пересчитываются. У вас есть scenario "Sber Online charges market fees" в financial model?
>
> **Лид:** ...Нет, не моделировали.

### Cap table / exit

> **Михаил:** Sber-affiliated structure. Какой ваш path-to-liquidity?
> - IPO через Sber Group в составе divestment — long-shot, нужен PE friendly market
> - Strategic acquirer not-Sber — есть foreign LPs которые поделятся cap table? Wildberries / Yandex / VTB? Все три заблокированы либо политически либо competitively
> - Buyback Sber Group по справедливой цене — единственный реалистичный путь, но цена будет deeply discounted
>
> **Лид:** Sber Group мажоритарий чтобы regulator чувствовал контроль; minority shareholders по mid-stage round с buyback right.
>
> **Михаил:** Buyback right в РФ работает плохо при разногласиях в valuation. Опишите конкретный clause.

### Tinkoff competition

> **Михаил:** Вы говорите "Tinkoff нужно 12–18 месяцев". У них уже есть Tinkoff Invest с 10М клиентов, у них есть Tinkoff Business с corp клиентами, у них есть собственная экосистема. У вас 6 месяцев lead в технологии, не в distribution. Объясните, почему 6 месяцев hard-tech lead transferable в multi-year market lead?
>
> **Лид:** SberID SSO + Sber Online distribution — конкретные barriers entry для них, не воспроизводимые.
>
> **Михаил:** ОК. А если Tinkoff подпишет с Yandex (тоже не любящий Sber) reciprocal SSO — Yandex ID как single sign-on для Tinkoff DLMM? У Yandex 90М users в Yandex.Plus.
>
> **Лид:** Этого ещё нет.
>
> **Михаил:** Right. Но это не impossible. Учитывайте в risk model.

### The ask part

> **Михаил:** 33М ₽ на Q3-Q4. Я могу дать это завтра из corporate development бюджета. Но я делаю это только если:
> 1. Bottom-up TAM модель (не сегодня — через 2 недели)
> 2. Scenario analysis "Sber Online market fees" (через 2 недели)
> 3. Конкретный buyback right clause в shareholders agreement (юристы готовят 4 недели)
> 4. **Месячные cohort метрики** (новые ачивменты MAU/retention каждый месяц — без этого вы не получаете 2-й транш)
> 5. Tinkoff intelligence — мы наймём Roland Berger на competitive deep-dive (4 недели)

### Что Михаил сказал бы LP комитету

> *"Хорошая команда, реальный продукт, не vapor. Но moats слабее чем они утверждают, и cap table делает exit маловероятным. Я бы пошёл с 25М ₽ tranched + аналитикой за 30 дней. Если bottom-up TAM выдержит — увеличиваем до 50М. Если нет — pull-out с минимальными потерями."*

### Извлечённые growth points (Investor-driven)

7. **G-07** Bottom-up TAM model по 3 сегментам (corp B2B / retail FX / corp tokenization) с явными assumptions
8. **G-08** Sensitivity analysis "Sber Online charges market fees" — что происходит с unit economics
9. **G-09** Detailed competitive intel: Tinkoff/VTB/Yandex job postings, GitHub footprints, Telegram leaks — quarterly tracking
10. **G-10** Cohort analytics → public-facing investor dashboard (read-only)
11. **G-11** Shareholders agreement buyback clause — точная цена + tag-along + drag-along
12. **G-12** Exit-scenario planning — какие 3 strategic acquirer'а realistic, что нужно чтобы они interested

---

## 3. ИП Анна, малый бизнес (5 мин)

**Audience:** Анна, 34 года, ИП с 2019 года, продаёт детские товары через WB и Озон в Китай, оборот ~80М ₽/год. Использует Сбер бизнес карту, СберБизнес мобильное. Активный пользователь Telegram и Wildberries Pay.

### Открытие

> **Лид:** *"Анна, у вас 5M ₽ просто лежат на расчётном счёте — мы можем это превратить в 60К ₽ дохода в год..."*
>
> **Анна:** *(посчитала на телефоне)* — 60К с пяти миллионов? Это 1.2% годовых. У меня СберВклад под 12% сейчас.
>
> **Лид:** Я неправильно сказал. ~570К ₽/год, не 60К. Это потому что net of protocol fee 5% — ~12% годовых на 5M.
>
> **Анна:** ОК. Слушаю.

### Хедж юаня

> *(показал FX hedge flow)*
>
> **Анна:** А если рубль вырастет на 10% за 30 дней, я по этому "зафиксированному курсу" заплачу больше, чем заплатила бы по рыночному?
>
> **Лид:** Да, в этом случае хедж "стоит" вам этой разницы. Но симметрично — если рубль упадёт, вы экономите.
>
> **Анна:** То есть это лотерея? Я не хочу лотерею. Я хочу гарантию что не потеряю на курсе.
>
> **Лид:** Это и есть гарантия. Вы фиксируете курс — больше не зависите от рынка. Это инструмент против неопределённости.
>
> **Анна:** *(пауза)* — Скажу честно, это сложно. У моего бухгалтера на эту тему 5 вопросов будет. *(пишет в Telegram)* Можно мне видео-объяснялку?

### Пул

> *(показал LP add)*
>
> **Анна:** "Spot стратегия, ±25 бинов от текущей цены"... Я не понимаю ничего из этих слов.
>
> **Лид:** Извините. Просто значит: ваши деньги работают в широком диапазоне цен, и зарабатывают комиссии когда люди торгуют SBER акциями через этот пул.
>
> **Анна:** А если SBER акции упадут на 30%? Что с моими деньгами?
>
> **Лид:** У вас будет смесь рублей и SBER акций — не только рублей. Если цена упала, у вас стало больше SBER (impermanent loss).
>
> **Анна:** Стоп. Вы только что сказали что я могу потерять. А до этого говорили "это просто как депозит".
>
> **Лид:** ...Не как депозит. Это инвестиционный инструмент с рыночным риском.
>
> **Анна:** Тогда мне нужна страховка АСВ. Есть?
>
> **Лид:** На LP позиции — нет. На SRUB-balance (просто рубли) — да, до 1.4M ₽.

### Auto-claim

> *(показал /profile → auto-claim)*
>
> **Анна:** Очень удобно. Но что если я закрою браузер — оно сработает?
>
> **Лид:** Нет, пока что только когда вкладка открыта. Backend-версия — в Sprint 11.
>
> **Анна:** То есть мне всё равно нужно сидеть с открытой вкладкой? Я открываю только когда что-то делаю. Зачем мне эта функция тогда?
>
> **Лид:** ...Хороший вопрос.

### Цена

> **Анна:** Сколько всё это стоит? У меня сейчас тариф ИП Бизнес-Старт, плачу 1500 в месяц.
>
> **Лид:** Free тариф включает свопы, хедж, LP — без месячной оплаты. Берём 5% от комиссий пулов и 0.3% от хеджа.
>
> **Анна:** ОК, бесплатно — это хорошо. KYC мне надо проходить отдельно или мой Сбер ID подойдёт?
>
> **Лид:** Сейчас отдельно — три документа загрузить. SberID интеграция в Sprint 10-11.
>
> **Анна:** Жалко. Я уже три раза проходила KYC в разных Сбер сервисах. Подождать.

### Final reaction

> **Анна:** Слушайте, продукт интересный. Но прямо сейчас — много "если". Если SberID, если background auto-claim, если видео-объяснялка. Я подожду до осени, посмотрю отзывы коллег из WB-сообщества. Можете в Telegram прислать ссылку когда будет готово?

### Что Анна напишет в WB-чате после встречи

> *"Девочки, тут Сбер какую-то новую штуку показал — типа можно деньги в пулы класть и юань хеджировать. Интересно, но сложно объяснять бухгалтеру и непонятно про риски. Подождём пока проверенные сделают первые отзывы."*

### Извлечённые growth points (Anna-driven)

13. **G-13** Видео-объяснялки: 2-3 минуты на ключевую фичу (хедж / LP / Health Score) с реальной русской дикторшей; embedded на каждом workflow first-use
14. **G-14** Risk disclosure UI: явная индикация "impermanent loss возможен", "АСВ не страхует", "это не депозит" на add-liquidity flow — не закопано в FAQ
15. **G-15** SberID SSO must-have (F-25 уже в Sprint 10 — приоритизировать)
16. **G-16** Auto-claim background mode (Sprint 11 backend swap) — без него фича бесполезна для retail
17. **G-17** Simple-mode toggle: "I'm new — спрячь bin/strategy/zoom; покажи только Buy/Sell"
18. **G-18** Plain-language glossary tooltip на каждом термине (bin, slippage, IL, LP) — onClick popover, не только hover
19. **G-19** Customer review surface — собственная страница "что говорят пользователи" с честными отзывами

---

## 4. Финдир Дмитрий, средний бизнес (10 мин)

**Audience:** Дмитрий, 41 год, финдир IT-компании 30 человек. CFA, 5 лет в industry CFO+ ролях. Подключён к четырём банкам параллельно (Сбер, Тинкофф, Райффайзен, Альфа). Знает свои workflow до уровня XML тегов в 1C интеграции.

### Открытие

> **Лид:** *"Дмитрий, прежде чем погрузимся в платформу — три цифры: ваши 50М ₽ под 0%, B2B спред 0.5%, хедж 70bps..."*
>
> **Дмитрий:** Один момент, "50М под 0%" — у меня деньги на овернайт repo по 18%, не на расчётном. Откуда вы взяли 0%?
>
> **Лид:** Это был общий пример для среднего бизнеса.
>
> **Дмитрий:** Не предполагайте про мой workflow до того как спросите. Дальше.

### B2B settlement

> *(показал поток "фрилансер из Армении")*
>
> **Дмитрий:** Spread 5bps вместо 50bps у SBP. Откуда эта цифра? SBP B2B сейчас 0% spread между Сбер счетами, и 0.1-0.3% с другими банками.
>
> **Лид:** 0.5% это для cross-border / non-bank counterparts, не для intra-bank.
>
> **Дмитрий:** ОК, тогда compare должен быть честный. У меня большинство B2B — Сбер ↔ Сбер, ваш product не даёт преимущества там. Где он даёт — это **non-Сбер** corp counterparts. Сколько таких сейчас на платформе?
>
> **Лид:** В sandbox — 3 пилотных corp клиента, в production пока нет non-Сбер counterparts.
>
> **Дмитрий:** Тогда ваш B2B settlement value сейчас ≈ 0 для меня. Я не могу делать платёж туда, где нет получателя.

### FX hedge

> *(показал хедж)*
>
> **Дмитрий:** 60-дневный хедж на 5М ₽. У вас квоут показал spread 30bps. Что входит в этот 30bps — bid-ask, fee, или что?
>
> **Лид:** Round-trip swap fee. 15bps на open + 15bps на settle.
>
> **Дмитрий:** ОК. У меня в Раффайзене есть FX forward на 60 дней под спред 12-15bps total. Чем ваш лучше?
>
> **Лид:** ...Цена сопоставима. Преимущество — единый интерфейс с остальными вашими операциями + 1С экспорт.
>
> **Дмитрий:** "Единый интерфейс" — это nice-to-have. У меня уже 4 интерфейса и я к ним привык. Покажите real cost benefit или мне неинтересно.

### Pool comparator

> *(показал /pools/compare с share link)*
>
> **Дмитрий:** Эта ссылка показывает casual data. Покажите 30d-volatility, max drawdown за период, Sharpe ratio. Без них я не могу LP-allocate в каких бы то ни было размерах.
>
> **Лид:** Pool comparator MVP, эти метрики — Sprint 11.
>
> **Дмитрий:** Запиши.

### LP add

> *(показал /pools/{id}/liquidity)*
>
> **Дмитрий:** OK хочу попробовать. 50М ₽ в SRUB/SBER, Spot, ±25 bins. Что если хочу 200M в SRUB/SBER? Какая максимальная positionsize до того как **мой own swap** moves bin? Это **price impact** на сам LP-add. У вас рассчитан в quote?
>
> **Лид:** На add-liquidity слип не рассчитывается, только на swap.
>
> **Дмитрий:** Запиши.

### 1С интеграция

> **Лид:** *"...прямой XML экспорт из transactions report в 1C..."*
>
> **Дмитрий:** XML какой версии? У меня 1С УПП 1.3 + ЗУП 8.3 модифицированная. Конвенция полей может отличаться.
>
> **Лид:** Sprint 5 #5.11 — 1С банк-клиент XML v3.0, базовая конвенция. С УПП 1.3 — нужна custom mapping.
>
> **Дмитрий:** То есть мне нужен middleware? Это +200К на разработку + поддержку.
>
> **Лид:** Можно отдать в наш T&M Engagement, ~5 PD по 200К/PD = 1М ₽ one-time.
>
> **Дмитрий:** 1М за 1С коннектор. Запомню. Кому-то это не понравится.

### Multi-account

> **Дмитрий:** У меня 3 финансовых менеджера. У каждого свой workflow: один по операционным расходам, второй по инвестициям, третий по налоговой отчётности. Как разграничить?
>
> **Лид:** Сейчас одна организация = один user account. Multi-user под одну организацию — Sprint 11+.
>
> **Дмитрий:** Это блокер. Я не дам всем троим один account / пароль. И audit log без user attribution мне регулятор не примет.

### 2FA / security

> **Дмитрий:** OAuth/SAML/SSO через мой Active Directory?
>
> **Лид:** SberID Sprint 11, корпоративный SSO (SAML) — не в planned roadmap.
>
> **Дмитрий:** 2FA TOTP минимум? Я не могу авторизоваться в финансовую платформу через просто email+password.
>
> **Лид:** ...Не реализован сейчас.
>
> **Дмитрий:** Стоп. **Это финансовая платформа без 2FA в 2026 году?** Это блокер.

### Disaster

> **Дмитрий:** Что если ваш Postgres лежит? Я не могу получить SRUB обратно. Бизнес простаивает.
>
> **Лид:** TD-6 runbook — dev-grade backup сейчас, WAL-G + S3 в Sprint 11.
>
> **Дмитрий:** Sprint 11 для prod-grade DR. ОК. Что у вас сейчас в production?
>
> **Лид:** Sandbox, не production.
>
> **Дмитрий:** То есть production нет. *(закрывает блокнот)* — я подумал что вы зрелый продукт с pilot клиентами. Зрелый продукт с pilot имеет prod-grade DR. Вы — sandbox. Перепригласите когда production-ready.

### Что Дмитрий скажет своему CEO

> *"Платформа концептуально интересная, технически серая. Sandbox без 2FA, без multi-user, без real production deployment. Я бы вернулся к разговору через 2 квартала когда они: (а) запустят production, (б) реализуют SSO + 2FA + multi-user, (в) покажут хотя бы 5 живых corp клиентов. До тех пор — risk не оправдывает экспериментирование."*

### Извлечённые growth points (Dmitry-driven)

20. **G-20** **2FA / TOTP** — критический gap для финансовой платформы (high priority)
21. **G-21** **Multi-user / sub-account под одной организацией** — с role-based permissions (Финансовый менеджер / Бухгалтер / Аудитор / Owner)
22. **G-22** Add-liquidity quote должен включать price impact на самом acte LP-add
23. **G-23** Pool comparator: 30d volatility, max drawdown, Sharpe ratio (для professional users)
24. **G-24** 1С коннекторы для разных версий (УПП 1.3, ЗУП 8.3, Бухгалтерия 3.0) — pre-built mappings, не каждый раз custom T&M
25. **G-25** Production deployment story (TD-4 + TD-5 — Helm + CI/CD pipeline) — должен быть first-class для зрелых клиентов
26. **G-26** Honest competitive comparison vs Раффайзен / Альфа FX forwards — должны выигрывать по чему-то конкретному, не "единый интерфейс"
27. **G-27** Account-level isolation: non-Sber B2B counterparts pre-onboard pipeline — без них B2B rail value = 0 для current corp клиентов
28. **G-28** Multi-user audit log с user attribution на каждой транзакции (regulatory compliance)
29. **G-29** SAML/SSO для corporate authentication (не только SberID retail-flow)

---

## 5. Александр Викторович, enterprise (15 мин)

**Audience:** Александр Викторович, 53 года, treasury director ПАО уровня. 30 лет в banking + corporate finance. Имеет PhD по эконометрике, ранее работал в Центробанке как senior advisor. Видит платформу глазами регулятора, бизнеса и юриста одновременно.

### Открытие

> *(strategic framing)*
>
> **АВ:** Sber Treasury пилот — на какую сумму, какая ставка return, что counted as success metric?
>
> **Лид:** Allocation 100М ₽ через дочку M&M Asset, return = realised pool fees, success = quarterly review достигаем 12%+ APY net of all fees.
>
> **АВ:** За какой период данные?
>
> **Лид:** Q3 2026 — первый квартал измерений, sample size ещё мал.
>
> **АВ:** Sample size мал — значит utility пока 0 для меня. Расскажите про теоретические foundations: какая ваша оценка ожидаемого APY распределения?
>
> **Лид:** ...У нас нет рassированной distribution model.
>
> **АВ:** То есть я инвестирую blind. Я ожидал бы от Sber-affiliated platform хотя бы Monte Carlo simulation для potential clients.

### Custom tokenization

> *(показал admin OTC)*
>
> **АВ:** ЦФА классификация — у вас memo готов, ОК. А кто будет depositary для tokenized bonds? У нас есть partnership с НРД, но НРД не работает с custodial-style записями.
>
> **Лид:** Sber как issuer + custodian в текущей модели. Мы — балансовый custodian, не depositary в классическом смысле.
>
> **АВ:** То есть мои tokenized bonds сидят на счёте Sber. Если Sber обанкротится — что становится с моими облигациями?
>
> **Лид:** Bonds underlying — наши, как issuer. SRUB balances — депозитные требования к Sber, страхование АСВ до 1.4M.
>
> **АВ:** 1.4М ₽ страхования для холдинга с 25B оборотом — это zero. Мне нужен **сегрегированный custody** через ICSD-аналог. Это в roadmap?
>
> **Лид:** Sprint 12+ — segregated custody через partnership с НРД или собственная depositary license.
>
> **АВ:** Депозитарную лицензию ЦБ выдаёт 18 месяцев минимум. Я бы вернулся через год обсуждать.

### SLA Market-Maker contract

> **АВ:** Spread guarantee ≤30bps 24/7. Что насчёт fat-tail events — например, geopolitical shock с volatility +200%?
>
> **Лид:** SLA имеет force majeure clause — Sber Treasury может suspend MM activity at black swan.
>
> **АВ:** Кто определяет "black swan"? Sber unilateral, или есть protocol с третьей стороной?
>
> **Лид:** Sber unilateral сегодня.
>
> **АВ:** Тогда это **не guarantee**, это **best-effort**. Перепишите term sheet.

### Multi-sig

> **АВ:** Multi-sig 2-of-3 — Sprint 12, понятно. Что подписывают эти 3 — JWT? Cryptographic signature?
>
> **Лид:** JWT signed by user's auth token. Signatures сохраняются в audit log.
>
> **АВ:** JWT — это soft signature, не cryptographic. У меня для 50M+ транзакций должны быть **HSM-backed signatures** с private keys из аппаратных ключей. Иначе compliance не подпишет.
>
> **Лид:** HSM integration — не в текущем backlog.
>
> **АВ:** Запиши.

### White-label DLMM-as-Service

> **АВ:** "Transport DLMM" — кто owner данных? Это наши клиенты, наши транзакции — данные должны быть в **нашем** дата-центре, под нашим контролем.
>
> **Лид:** SaaS модель — данные в Sber Cloud, изолированный tenant.
>
> **АВ:** SaaS с нашими корп данными у Sber? Compliance не позволит. Должна быть on-premise опция.
>
> **Лид:** ~50М ₽ engineering effort для on-prem deployment, T&M.
>
> **АВ:** ОК, тогда давайте конкретно: тендер на on-prem deployment + 1 год support + escrowed source code. Можем подписаться, если цифры адекватные.

### Regulatory liability

> **АВ:** ЦБ проверка — Sber несёт ответственность за compliance моих транзакций или я?
>
> **Лид:** Sber как infrastructure provider, ответственность по делу — split. Compliance officer ваш организации видит наш audit log и принимает решения.
>
> **АВ:** Split — это юристы любят неточные слова. Напишите конкретно: какие компоненты compliance lie на Sber (платформа), какие на нас (бизнес-логика наших транзакций). Иначе мы конфликтуем при первой проверке.

### IT-security

> **АВ:** Pentest — Positive Technologies, ОК. Какой scope? Black-box / grey-box / white-box?
>
> **Лид:** Grey-box, доступ к OpenAPI документации + аутентифицированные user/admin аккаунты.
>
> **АВ:** Минимум должен быть white-box с source code access. Грей box не находит logic bugs.

### Operational

> **АВ:** Insurance:
> - Cyber Liability — наличие $50M+ policy?
> - Crime / employee fidelity — да?
> - E&O — да?
> - Custodial-specific insurance (на pool exploit losses)?
>
> **Лид:** Sber Group имеет corporate insurance, специально про DLMM Platform — отдельной policy нет.
>
> **АВ:** Я не могу сидеть на $25B оборота без специальной DLMM-coverage. Скажите PMs — нужна dedicated cyber insurance минимум $100M aggregate.

### BCP/DRP

> **АВ:** Data center в Москве лежит — что у вас?
>
> **Лид:** Sber Cloud имеет multi-AZ deployment в Москве + Sankt-Peterburg.
>
> **АВ:** Тестировали failover? Когда последний раз?
>
> **Лид:** Sber Cloud level — да, регулярно. DLMM Platform-level failover test — Sprint 11.
>
> **АВ:** Тестируйте до того как мы подключаемся.

### Pricing

> **АВ:** Enterprise тариф от 2М ₽/мес базовый + SLA-MM 50М/год + white-label 30М one-time + 1% volume. Грубо — 300-800М ₽/год для нас.
>
> **Лид:** Yes, ROI ~720М ₽/год экономии — окупается ~1×.
>
> **АВ:** "Экономия 720М" — assumption. Я хочу видеть это в **performance-linked pricing**: вы получаете base + bonus при достижении конкретных KPI. Тогда align'м interests.

### Closing

> **АВ:** Александр, я ценю что Sber инвестирует в эту инфраструктуру — это правильное стратегическое направление. Но для **production engagement** Транспортхолдинг'а мне нужны:
> 1. Сегрегированный custody через partnership с НРД (или собственная депозитарная лицензия)
> 2. HSM-backed multi-sig signatures (не JWT)
> 3. On-premise white-label deployment с escrowed source code
> 4. White-box pentest от Positive или Лаборатория Касперского
> 5. Dedicated cyber insurance $100M+
> 6. Tested DR failover Москва ↔ СПб
> 7. Performance-linked pricing model
> 8. Conservative APY distribution model для LP allocation
> 9. Concrete compliance split (Sber vs Транспортхолдинг responsibility matrix)
>
> Без этих — вы тёплый pilot. С ними — мы серьёзный partner.

### Что АВ скажет на следующем board meeting

> *"Sber DLMM Platform — потенциально strategic для нас, но не production-ready для холдинга нашего масштаба. Я предложил им 9-pointlist of requirements. Если выполнят к Q1 2027 — мы начинаем pilot phase 1 на 50M allocation. Если нет — отложим и наблюдаем еще год."*

### Извлечённые growth points (АВ-driven)

30. **G-30** Сегрегированный custody через partnership с НРД (или собственная депозитарная лицензия — multi-year)
31. **G-31** **HSM-backed signatures** для multi-sig (не JWT) — Sprint 12+ переплан
32. **G-32** On-premise / dedicated tenant deployment option (T&M ~50M, но pre-built)
33. **G-33** White-box pentest (не только grey-box) — Sprint 11
34. **G-34** Dedicated cyber insurance policy $100M+ для DLMM Platform (не general Sber corporate)
35. **G-35** Tested DR failover — quarterly drills (Sprint 11+)
36. **G-36** Performance-linked pricing model — base + KPI-bonuses (для enterprise tier)
37. **G-37** Monte Carlo APY distribution model для LP allocation decisions
38. **G-38** Compliance split matrix: "что лежит на Sber как infrastructure / что на client как business logic" — legal doc
39. **G-39** Force-majeure / black-swan клозурный protocol с третьей стороной (не Sber unilateral)
40. **G-40** Source code escrow для enterprise white-label deployments

---

## 6. Synthesis — growth points backlog

### По частоте упоминания через personas

| Тема | VP | Investor | Anna | Dmitry | АВ | Всего |
|---|:---:|:---:|:---:|:---:|:---:|:---:|
| Cohort analytics / DAU/MAU | ✅ | ✅ | — | — | — | 2 |
| Risk disclosure UX | — | — | ✅ | — | — | 1 |
| Trust signals (АСВ, reference cases) | — | — | ✅ | ✅ | ✅ | 3 |
| Plain-language education | — | — | ✅ | — | — | 1 |
| 2FA / SSO / SAML | — | — | mention | ✅ | mention | 2+ |
| Multi-user / sub-account | — | — | — | ✅ | mention | 1+ |
| Production-grade DR / DR drills | — | — | — | ✅ | ✅ | 2 |
| Honest competitive analysis | — | ✅ | mention | ✅ | — | 2+ |
| Bottom-up TAM (vs top-down) | — | ✅ | — | — | — | 1 |
| Cohort retention metrics | ✅ | ✅ | — | — | — | 2 |
| Slippage / price impact on LP add | — | — | mention | ✅ | mention | 1+ |
| Pool comparator: vol / Sharpe | — | — | — | ✅ | mention | 1+ |
| 1С коннекторы для разных версий | — | — | — | ✅ | — | 1 |
| Custom HSM-backed signatures | — | — | — | — | ✅ | 1 |
| On-prem / dedicated tenant | — | — | — | mention | ✅ | 1+ |
| Insurance (DLMM-specific cyber) | — | — | — | — | ✅ | 1 |
| Performance-linked pricing | — | — | — | — | ✅ | 1 |
| Auto-claim background mode | — | — | ✅ | — | — | 1 |
| SberID SSO retail | — | mention | ✅ | — | — | 1+ |
| Compliance split matrix | — | — | — | mention | ✅ | 1+ |
| Quote-execute idempotency UX | ✅ | — | — | — | — | 1 |
| Health Score "что делать" | ✅ | — | — | — | — | 1 |
| Video onboarding tutorials | — | — | ✅ | — | — | 1 |

### По impact × effort matrix

```
                       LOW EFFORT          MEDIUM EFFORT         HIGH EFFORT
                   ┌──────────────────┬───────────────────┬─────────────────────┐
   HIGH IMPACT     │ G-03 Health      │ G-20 2FA TOTP     │ G-21 Multi-user     │
   (clears        │  Score CTA       │ G-15 SberID SSO   │  + role permissions │
    blocker)      │ G-14 Risk disc.  │ G-16 Auto-claim   │ G-25 Prod deployment│
                   │ G-18 Glossary    │  backend          │  (TD-4 + TD-5)      │
                   │ G-23 Pool comp   │ G-22 LP add slip  │ G-30 НРД custody    │
                   │  analytics       │ G-29 SAML SSO     │ G-32 On-prem option │
                   │                  │ G-01 Cohort dash  │                     │
                   │                  │ G-35 DR drills    │                     │
                   ├──────────────────┼───────────────────┼─────────────────────┤
   MED IMPACT      │ G-04 Public      │ G-13 Video        │ G-31 HSM mit-sig    │
                   │  share-link      │  tutorials        │ G-33 White-box      │
                   │  restrictions    │ G-24 1С           │  pentest            │
                   │ G-05 Top         │  коннекторы pre-  │ G-37 Monte Carlo    │
                   │  throttled       │  built            │  APY distribution   │
                   │ G-19 Customer    │ G-09 Competitive  │                     │
                   │  reviews surface │  intel quarterly  │                     │
                   ├──────────────────┼───────────────────┼─────────────────────┤
   LOW IMPACT      │ G-17 Simple-mode │ G-07 Bottom-up    │ G-40 Source code    │
                   │  toggle          │  TAM model        │  escrow             │
                   │ G-26 Honest      │ G-11 Shareholders │                     │
                   │  competitive doc │  buyback clause   │                     │
                   │                  │ G-38 Compliance   │                     │
                   │                  │  split matrix     │                     │
                   └──────────────────┴───────────────────┴─────────────────────┘
```

---

## 7. Sprint allocation

### Sprint 11 (next 2 weeks) — Trust + Security wave

**Theme:** убрать blocker'ы которые мешают medium business + enterprise сесть на платформу.

| ID | Item | Effort | Driver |
|---|---|---|---|
| G-20 | **2FA TOTP** для всех accounts | M (3d) | Dmitry blocker |
| G-21 | **Multi-user под одной организацией** + Role-based permissions (Owner / Finance Manager / Accountant / Auditor) | L (5d) | Dmitry blocker |
| G-15 | **SberID SSO retail** (F-25 ускорить, не Sprint 12) | M (3d code post-contract) | Anna + 1+ persons |
| G-22 | **Add-liquidity quote with price-impact** | S (1d) | Dmitry |
| G-23 | **Pool comparator: 30d vol / max drawdown / Sharpe** | S (1.5d) | Dmitry |
| G-35 | **Tested DR failover drill** в staging (Postgres + Kafka manual kill) | S (1d) | АВ + Dmitry |

**Total:** ~14d, fits 1 sprint of 2 backend + 1 FE + 1 SRE.

### Sprint 12 — UX + Trust Layer 2

**Theme:** дальше укрепить trust signals + retail education.

| ID | Item | Effort |
|---|---|---|
| G-14 | Risk disclosure UI на add-liquidity ("не АСВ", "impermanent loss возможен") | S (0.5d FE) |
| G-18 | Plain-language glossary onClick popover на bin/slippage/IL/LP terms | S (1d FE) |
| G-13 | Video onboarding tutorials (3 видео, 2-3 min each — outsource production) | M (5d total: 3d marketing + 2d FE embed) |
| G-03 | Health Score "что делать" — кнопка "ребалансировать эту позицию" из Tooltip | S (1d FE) |
| G-16 | **Auto-claim background mode** — backend swap-in (POST /api/v1/users/auto-claim-policy + @Scheduled) | M (3d backend + 1d FE migration) |
| G-01 | **Cohort analytics dashboard** (DAU/MAU/D7/D30 retention) — admin-bff + admin-ui | M (3d) |
| G-19 | Customer reviews surface — простая страница "что говорят" | S (1d FE) |
| G-17 | Simple-mode toggle (hide advanced features by default) | S (1d FE) |
| G-29 | SAML/SSO для corporate authentication | M (3d) |
| G-02 | Quote-execute idempotency story documented + automated test | S (1d) |

### Sprint 13+ — Enterprise foundation

**Theme:** unblock крупных клиентов (Транспортхолдинг и аналогичные).

| ID | Item | Effort | Notes |
|---|---|---|---|
| G-25 | **Production deployment story** (Helm chart + CI/CD pipeline) (TD-4 + TD-5) | L (8d) | Critical for "are you production ready" question |
| G-24 | 1С коннекторы для УПП 1.3 / ЗУП 8.3 / Бухгалтерия 3.0 — pre-built mappings | M (5d total) | |
| G-28 | Multi-user audit log с user attribution per transaction | M (2d) | Regulatory |
| G-32 | On-premise deployment option | XL (15d engineering) | T&M per client |
| G-33 | White-box pentest by Positive Technologies | (external) | Sprint 13 spend |
| G-38 | Compliance split matrix (legal doc) | M (5d legal + 2d engineering doc) | |
| G-37 | Monte Carlo APY distribution model | M (3d analyst + 2d engineering) | For LP allocation decisions |
| G-39 | Force-majeure protocol with third-party adjudication | M (legal) | |

### Sprint 14+ — Cap table + Strategic

**Theme:** investor-facing items + enterprise differentiation.

| ID | Item | Effort | Notes |
|---|---|---|---|
| G-07 | Bottom-up TAM model по 3 сегментам | M (2 analyst weeks) | Outside engineering |
| G-08 | Sensitivity analysis "Sber Online market fees" | M (1 analyst week) | |
| G-10 | Cohort metrics dashboard для investor read-only access | S (1d FE + permissions) | |
| G-11 | Shareholders agreement buyback clause | M (4 weeks legal) | |
| G-12 | Exit-scenario planning | M (strategic doc) | |
| G-34 | Dedicated cyber insurance $100M+ policy | M (procurement) | |
| G-36 | Performance-linked pricing model для enterprise tier | M (3d legal + 2d engineering) | |

### Sprint 15+ — Long-term moats

| ID | Item | Notes |
|---|---|---|
| G-30 | **Сегрегированный custody через НРД partnership** ИЛИ собственная депозитарная лицензия | 12-18 months (regulatory) |
| G-31 | HSM-backed signatures для multi-sig | M-L; integration with Sber HSM infra |
| G-40 | Source code escrow для enterprise white-label | M (procurement + legal) |

---

## 8. Топ-5 квартальных insights

### 1. "Trust is the limiting factor for medium business+"

Дмитрий и АВ оба остановились на **2FA missing** и **production-grade DR**. Это базовые expectations для **финансовой** платформы. До их закрытия revenue от corporate tier — capped.

**Action:** Sprint 11 wave должен быть "Security + Trust" блок. После закрытия — pricing power для Pro / Enterprise tariff'ов растёт.

### 2. "Sber distribution is rented, not owned"

Михаил (investor) указал на хрупкость SberID SSO moat. Если Sber Online BU pricing меняется — unit economics ломаются.

**Action:** в financial model — добавить scenario "Sber Online charges market fees". Это формирует buffer для future re-pricing discussions с Sber Group leadership.

### 3. "Retail без SberID = no retail"

Anna и Dmitry оба упомянули, что KYC через "3 документа загрузить" — friction. SberID SSO = no friction.

**Action:** **F-25 SberID SSO** должен быть **first priority** Sprint 11, не Sprint 12.

### 4. "Health Score без action = noise"

Елена и Anna обе спросили "что мне с этим делать". Score без actionable recommendation = decorative.

**Action:** G-03 Health Score CTA "ребалансировать эту позицию" — простой win, 1 день FE.

### 5. "Enterprise requires institutional-grade custody"

АВ блокирован 1.4M АСВ страхованием на 25B portfolio. Без сегрегированного custody через НРД — Enterprise tier остаётся theoretical.

**Action:** Sprint 12+ — **НРД partnership exploration** (это не code задача, это business development). Параллельно — proxy custody через крупный depositary partner.

---

## 9. Что Я (presenter) сделал бы по-другому

After running all 5 simulations:

1. **VP demo:** не показывал бы 7 экранов. Лучше — 2 экрана + 5 минут на cohort/retention discussion. Они хотят numbers, не feature tour.

2. **Investor demo:** TAM slide пере-сделал бы bottom-up до встречи. Top-down — это amateur hour для experienced investor.

3. **Anna demo:** убрал бы "Spot / Curve / Bid-Ask" termology полностью. Заменил на "Простой" / "Концентрированный" / "Краевой" stratrgy. Plus — video.

4. **Dmitry demo:** начинал бы с **honest comparison** "вот что у вас сейчас, вот что у нас, вот в чём конкретно мы лучше". Не вообще "treasury workflow".

5. **АВ demo:** не показал бы admin интерфейс. АВ хочет видеть **process** — onboarding flow, compliance flow, support flow — не UI. Custom presentation с process diagrams была бы лучше.

---

## Sign-off

5 demos simulated, 40 growth points extracted, mapped в Sprint 11–15 backlog. Top-5 insights actionable starting Sprint 11.

— Sber DLMM Platform team · 2026-05-22
