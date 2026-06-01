# Математика свопов, комиссий и ликвидности (DLMM)

> Справочник для разработчиков по денежному пути `dlmm-pool-engine`: цена бинов,
> алгоритм свопа, комиссии (base + variable), инвариант F‑12, проскальзывание и
> поведение при **исчерпании ликвидности пула**. Все формулы — со ссылками на
> код. Числа в примерах проверены на живом стенде (раздел 8).

Ключевые файлы:
- `dlmm-common/.../util/BinMath.java` — цена ↔ бин, инвариант ликвидности, fee‑growth.
- `dlmm-common/.../util/FeeCalculator.java` — base + variable комиссия, volatility accumulator.
- `dlmm-pool-engine/.../service/SwapService.java` — `quote()` (расчёт) и `swap()/swapTransactional()` (исполнение).

---

## 1. Единицы и соглашения

| Величина | Единица | Замечание |
|---|---|---|
| Сумма токена | целое `long`, **1 ед. = 10⁻⁴ токена** | единый платформенный масштаб (#14); бэкенд НЕ применяет `decimals` токена. Фронт конвертит raw↔human на границе API (`scale.ts`). |
| Цена | `price = Y за 1 X` (token_y за token_x) | масштаб **не** меняет цену (это отношение Y/X). |
| `binStep` | базисные пункты (бп), 1 бп = 1/10000 | шаг геометрической лестницы цен. |
| Комиссия | бп от **входной** суммы | округление **FLOOR** (никогда не переплачиваем). |
| Базовая котировальная валюта | **SRUB** | почти все пулы — `X/SRUB`, где Y = SRUB. |

Бины нумеруются по конвенции Trader Joe LB: **`activeBinId = 2²³ = 8 388 608`** — это «якорь», где `price ≡ basePrice`.

---

## 2. Лестница цен по бинам

Цена бина — геометрическая прогрессия от базовой:

```
price(binId) = basePrice · (1 + binStep/10000)^(binId − activeBinId)
```

Код: `BinMath.binPriceAtBin(basePrice, binStep, binId, activeBinId)` (стр. 101) —
он сам вычитает `activeBinId`, поэтому **в бизнес‑коде нельзя** звать
`binPrice(basePrice, binStep, binId)` с абсолютным `binId` (возведёт множитель в
степень ~8 млн → бессмысленная цена без исключения; инцидент Sprint 9‑DS‑r3).

Обратное преобразование (цена → ближайший бин): `BinMath.priceToBinId` =
`round( ln(price/basePrice) / ln(1 + binStep/10000) )`.

`binPrice` использует `pow(int)` (square‑and‑multiply, O(log n)) — раньше был
наивный O(N) цикл, и 8.3М умножений на пул вешали `/api/v1/pools` на ~17с.

---

## 3. Бин = две стороны резерва + инвариант F‑12

Каждый бин хранит `reserveX`, `reserveY` и `liquidity`. **Инвариант F‑12**
(`BinMath.binLiquidity`, стр. 239):

```
liquidity = reserveX · price + reserveY      (всё в единицах token_y)
```

Распределение по бинам в каноническом DLMM:
- **бины ниже активного** держат **Y** (т.е. SRUB) — в них «уходит» продажа X→Y;
- **бины выше активного** держат **X** — в них «уходит» покупка Y→X;
- **активный бин** держит обе стороны.

Если резервы засеяны так, что `liquidity ≠ reserveX·price + reserveY`, то
изолированная add→remove (без свопов между) возвращает не ту сумму (семейство
багов F‑12). Реконсиляция: `docker/10-seed-reconcile-bin-invariant.sql`.

---

## 4. Алгоритм свопа (bin‑walk) — `SwapService.quote()`

Направление: `swapXtoY = (tokenIn == pool.tokenX)`.
- **X→Y** (продажа волатильного за SRUB): идём **ВНИЗ** (`binId − 1`), забирая `reserveY`.
- **Y→X** (покупка за SRUB): идём **ВВЕРХ** (`binId + 1`), забирая `reserveX`.

Цикл (стр. 260, `while remainingAmountIn > 0 && iterations < MAX_BIN_ITERATIONS`):

```
для текущего бина:
  если бин пуст (null или liquidity ≤ 0) → перейти к следующему, continue
  binPrice = bin.price (или пересчёт через BinMath, если не задан)

  # сколько ВХОДА этот бин может поглотить:
  X→Y:  maxAmountIn = floor(bin.reserveY / price)     # бин отдаёт Y
  Y→X:  maxAmountIn = floor(bin.reserveX · price)      # бин отдаёт X
  если maxAmountIn ≤ 0 → следующий бин, continue

  actualAmountIn = min(remainingAmountIn, maxAmountIn)
  fee     = FeeCalculator.calculateSwapFee(actualAmountIn, baseFeeBps, VA, binStep)
  netIn   = actualAmountIn − fee

  # выход:
  X→Y:  amountOut = floor(netIn · price)
  Y→X:  amountOut = floor(netIn / price)

  totalAmountOut   += amountOut
  totalFee         += fee
  remainingAmountIn −= actualAmountIn
  если actualAmountIn ≥ maxAmountIn:    # бин исчерпан
      перейти к следующему бину; binsCrossed++
```

Константа `MAX_BIN_ITERATIONS = 1000` — потолок числа бинов за один своп (см. §7).

Комиссия берётся **внутри каждого бина** с `actualAmountIn` (а не один раз со всей
суммы), поэтому при пересечении многих бинов с растущим VA эффективная ставка
может меняться по ходу свопа.

---

## 5. Комиссия = base + variable (форма Meteora) — `FeeCalculator`

```
totalFeeBps = min( baseFeeBps + variableFeeBps(VA, binStep),  MAX_FEE_BPS=1000 )   # ≤ 10%
fee(amountIn) = floor( amountIn · totalFeeBps / 10000 )                            # BigInteger, FLOOR
```

**Переменная часть** (по форме Meteora `compute_variable_fee`):

```
variableFeeBps = floor( VARIABLE_FEE_CONTROL · (VA · binStep)² / 1e11 )
                 VARIABLE_FEE_CONTROL = 50 000   (provisional, Stage 1)
```

- При **VA = 0** надбавка ровно 0 → `totalFeeBps == baseFeeBps`. Все засеянные пулы стартуют с VA=0, поэтому их комиссия = базовой.
- Калибровка (provisional): `VA=100, binStep=20 → 2 бп`; `VA=1000, binStep=20 → 200 бп`; `VA=10, binStep=100 → 0 бп`.

**Volatility accumulator (VA)** — «память волатильности», сырой счётчик пройденных бинов:
- После свопа: `VA = min(VA + |binsCrossed|, maxVolatility)` (`updateVolatilityAccumulator`), где `maxVolatility = pool.maxVariableFeeBps · 100`.
- Между свопами планировщик гасит: `VA = floor( VA · (10000 − r)/10000 )`, `r = decayRate` клампится в `[0,10000]` (`decayVolatilityAccumulator`; иначе при периоде <60с VA обнулялся каждый тик — баг C‑3).

**Сплит комиссии**: суммарная (gross) идёт в `totalFeesCollected{X,Y}`, доля протокола — отдельно в `totalProtocolFee{X,Y}` (для будущего sweep казны). LP‑доля раздаётся через fee‑growth (§6).

---

## 6. Раздача комиссий LP — fixed‑point fee‑growth (`BinMath`)

Прямое `lpFee / liquidity` в `long` почти всегда floor‑ится в 0 (комиссия одного
свопа меньше ликвидности бина) → LP не получали ничего. Поэтому инкремент
масштабируется на `FEE_GROWTH_SCALE = 1e9`:

```
feeGrowthIncrement = floor( lpFee · 1e9 / liquidity )        # копится в аккумуляторе бина
feeFromGrowth      = floor( (Δgrowth · shares) / 1e9 )       # выплата позиции; делит масштаб обратно
```

---

## 7. Исчерпание ликвидности пула — поведение (ПРОТЕСТИРОВАНО)

Что происходит, когда просят больше, чем пул может отдать в сторону свопа:

1. Bin‑walk проходит бины, пока есть `remainingAmountIn` **и** `iterations < 1000`.
2. Когда все бины с резервами в этом направлении исчерпаны, оставшиеся итерации
   попадают в **пустые** бины (skip, без потребления входа) — до потолка 1000.
3. Цикл выходит. Результат:
   - **`totalAmountOut` упирается в потолок** = вся доступная ликвидность в эту сторону;
   - **`consumedAmountIn` < запрошенного** — частичное заполнение (потребляется только то, что реально поглощено);
   - **исключение НЕ бросается** (т.к. `totalAmountOut > 0`).
4. `quote()` возвращает `consumedAmountIn` (честно — фактически потребляемую часть), не запрошенную сумму (стр. 381).
5. **Исключение `INSUFFICIENT_LIQUIDITY`** (стр. 686) бросается **только** если `totalAmountOut ≤ 0` — то есть когда в сторону свопа вообще **нет** резервов (полностью пустая сторона).

**Замеры (пул SUSDT/SRUB, продажа SUSDT→SRUB, base fee 10 бп):**

| amountIn (raw) | binsCrossed | priceImpact | amountOut (raw) |
|---|---|---|---|
| 1e6 … 1e11 | 0 | 0 % | линейно (≈70.9 за 1e6) |
| 5e11 | 2 | 0.089 % | 35.44e12 |
| 1e12 | 4 | 0.14 % | 70.85e12 |
| **5e12** | **1000 (потолок)** | **0.2804 %** | **168.64e12** |
| 1e13 … 1e16 | 1000 | 0.2804 % | **168.64e12 (не растёт)** |

Видно «плато»: с ~5e12 выход замирает на 168.64e12 — это весь `reserveY` ниже
активного бина. Сколько ни проси больше — отдадим только это, потратив
соответствующую часть входа.

> **UX‑следствие.** Котировка на исчерпании показывает частичное заполнение через
> `consumedAmountIn`, но поле «Вы отдаёте» в UI остаётся равным тому, что ввёл
> пользователь. Для крупных сделок стоит явно сигналить «частичное исполнение /
> недостаточно ликвидности на весь объём» (кандидат на доработку UI).

---

## 8. Денежный путь исполнения — `swapTransactional()` (порядок проверок)

Порядок важен — он определяет, какую именно ошибку увидит пользователь:

1. `POOL_NOT_FOUND` / `POOL_NOT_ACTIVE` — пул есть и активен.
2. Входной токен активен (иначе `POOL_NOT_ACTIVE`).
3. **KYC**: `userServiceClient.isUserKycVerified(userId)` → иначе `FORBIDDEN`. **Fail‑closed**: при недоступности user‑service считаем «не верифицирован».
4. Самозапрет 115‑ФЗ → `USER_SELF_RESTRICTED`.
5. Лимит одной сделки по пулу → `COUNTERPARTY_LIMIT_EXCEEDED`.
6. **Bin‑walk** (§4) → `INSUFFICIENT_LIQUIDITY`, если `totalAmountOut ≤ 0`.
7. **Проскальзывание**: если `minAmountOut > 0 && totalAmountOut < minAmountOut` → `SLIPPAGE_EXCEEDED`.
8. **Flush** (оптимистичная блокировка `@Version`) — ДО кросс‑сервисного расчёта, чтобы конфликт версий не привёл к двойному списанию.
9. **Дебет входного токена** в token‑service → `INSUFFICIENT_BALANCE`, если на балансе меньше `consumedAmountIn`.
10. Кредит выхода, обновление `activeBinId`, VA, TVL, событие через transactional outbox.

> Поэтому «Insufficient balance» приходит **после** успешного bin‑walk и проверки
> проскальзывания: ликвидности и цены хватило, не хватило средств на балансе.

**Конкуренция**: `swap()` оборачивает `swapTransactional()` в retry‑цикл
(`MAX_SWAP_ATTEMPTS = 5`, джиттер‑бэкофф) на случай
`ObjectOptimisticLockingFailureException` при свопах в один пул.

**TVL**: добавляется **нетто**‑вход (`consumedAmountIn − fee`), вычитается
`totalAmountOut` — чтобы `totalTvl` совпадал с суммой резервов бинов (комиссия
живёт в `totalFeesCollected*`, не в резервах).

---

## 9. Цена исполнения и price impact

`executionPrice` нормализуется в кадр `Y за X` (для Y→X берётся обратное
отношение), чтобы вычитание из `spotPrice` не давало ~100 % (баг Sprint 9‑DS‑r2).
`spotPrice = pool.basePrice` (а не `binPrice` при `activeBinId = 2²³`, который
переполнялся). `priceImpact` — чистое проскальзывание (без комиссии),
`computePriceImpact(...)`.

---

## 10. Справочник кодов ошибок (для фронта/QA)

| errorCode | HTTP | Когда |
|---|---|---|
| `INSUFFICIENT_BALANCE` | 400 | на балансе < `consumedAmountIn` (дебет входа) |
| `INSUFFICIENT_LIQUIDITY` | 400 | в сторону свопа нет резервов (`totalAmountOut ≤ 0`) |
| `SLIPPAGE_EXCEEDED` | 400 | `totalAmountOut < minAmountOut` |
| `POOL_NOT_ACTIVE` / `POOL_NOT_FOUND` | 400 / 404 | пул неактивен / не найден |
| `FORBIDDEN` | 403 | KYC не пройден (или user‑service недоступен — fail‑closed) |
| `USER_SELF_RESTRICTED` | 403 | самозапрет 115‑ФЗ |
| `COUNTERPARTY_LIMIT_EXCEEDED` | 400 | сумма > лимита одной сделки по пулу |
| `IDEMPOTENCY_CONFLICT` | 409 | дублирующийся `idempotencyKey` |
| `SLIPPAGE_EXCEEDED` | 400 | см. выше |

Фронт переводит эти коды в дружелюбный текст: `dlmm-user-ui/src/lib/apiError.ts`.

---

## 11. Привязка к внешнему рынку (price sync)

**Главное: цены бинов привязаны к реальному рынку, а ликвидность (резервы) — наша внутренняя.**

`PoolPriceSyncService` (Sprint 16) каждую минуту (`@Scheduled`, старт +25с):
1. Читает реальные спот‑цены из `price_feeds` (**CoinGecko** — крипто; **ЦБ РФ** — FX и драгметаллы; **акции синтетические** — MOEX ISS геоблокнут из этого окружения).
2. Для каждого ACTIVE‑пула считает целевую цену `Y за X` через рублёвые ориентиры (`targetPrice`).
3. Если рынок сдвинулся **> 0.2%** (`MIN_REL_CHANGE`) против текущего `basePrice` — репрайсит пул через `PoolRepricer` (суб‑0.2% движения пропускаются, чтобы бины не «дёргались» на шуме).

`PoolRepricer.reprice(poolId, target)`:
- `factor = target / oldBasePrice`; **вся лестница** бинов умножается на `factor` (геометрический шаг сохраняется).
- **Резервы НЕ трогаются** — LP держат те же токены; двигаются только цены, «как если бы рынок ушёл и арбитраж довёл пул туда».
- Для каждого бина `liquidity` и `compositionFactor` пересчитываются из новой цены → **инвариант F‑12 сохраняется**.
- `basePrice = target`; `activeBinId` **не двигается** (бин на нём автоматически снова имеет `price == basePrice`).
- Своя транзакция: конфликт оптимистичной блокировки с параллельным свопом откатывает только этот пул (повтор на следующем цикле).
- Выключатель: `dlmm.pool.price-sync-enabled` (по умолчанию `true`).

**Следствия:**
- **Внешняя цена изменилась, а у нас нет** → в течение ≤ 1 минуты (и при движении > 0.2%) sync подтянет пул к рынку. Долгого расхождения нет.
- Репрайс **не создаёт и не сжигает деньги**: токены LP те же, меняется только их оценка (как при движении рынка) — value‑neutral.
- Это **синтетическая привязка** (планировщик двигает цену напрямую) — в демо нет реальных арбитражёров; в настоящем on‑chain DLMM цену двигали бы только свопы/арбитраж.
- Окно расхождения: до ~1 минуты или суб‑0.2% шум — кратко и безопасно (резервы неизменны). Композиция активного бина «подравнивается» первым же свопом после репрайса.
- **Зависит от живого фида**: если `price_feeds` пуст (price‑oracle не достучался до CoinGecko/ЦБ — например, нет интернета на боксе), цикл пропускается и пул сохраняет последнюю цену. Пустой фид НЕ обнуляет цены.

## 12. Известные упрощения / зоны развития

- **VA‑масштаб** отличается от Meteora (у нас сырой счётчик бинов, у них ×1e4); `VARIABLE_FEE_CONTROL` откалиброван под наш масштаб. Перенос VA в кадр Meteora + per‑pool `variable_fee_control` — Stage 2.
- **Округление FLOAT** в `priceToBinId` (выбор бина, не точная арифметика).
- **Частичное исполнение** на исчерпании не подсвечивается в UI (§7).
- Полная dimensionally‑correct модель ликвидности — `LbDlmmMath` (миграция со смешанных единиц `BinMath`).
