/**
 * Maps a backend {@code ErrorResponse.errorCode} to a short, human-friendly
 * Russian message for end users.
 *
 * Why this exists: the gateway returns `{ errorCode, message, timestamp,
 * traceId }`, and the raw `message` is written for operators — it leaks
 * internal UUIDs and English text (e.g. "Insufficient balance for user
 * a0000000-… token b0000000-…"). Showing that to a trader is confusing and
 * exposes internals. We translate the STABLE `errorCode` to a friendly phrase
 * and, for any unmapped code, fall back to a generic message rather than
 * surfacing the raw (UUID-leaking) backend text.
 *
 * Codes mirror the DlmmException subclasses in dlmm-common
 * (com.sber.dlmm.common.exception.*).
 */
const FRIENDLY_BY_CODE: Record<string, string> = {
  INSUFFICIENT_BALANCE: 'Недостаточно средств на балансе для этой операции.',
  INSUFFICIENT_LIQUIDITY: 'В пуле недостаточно ликвидности для такого объёма — попробуйте сумму поменьше.',
  SLIPPAGE_EXCEEDED: 'Цена изменилась сильнее допустимого проскальзывания. Обновите котировку или увеличьте допуск.',
  POOL_NOT_ACTIVE: 'Пул сейчас неактивен.',
  POOL_NOT_FOUND: 'Пул не найден.',
  TOKEN_NOT_FOUND: 'Токен не найден.',
  FORBIDDEN: 'Для этой операции нужна верификация (KYC).',
  USER_SELF_RESTRICTED: 'Действует самозапрет (115-ФЗ): новые операции запрещены. Снять можно в профиле.',
  COUNTERPARTY_LIMIT_EXCEEDED: 'Сумма превышает лимит одной операции по этому пулу.',
  INVALID_BIN_RANGE: 'Некорректный диапазон бинов для позиции.',
  LIMIT_ORDER_INVALID: 'Некорректные параметры лимитного ордера.',
  IDEMPOTENCY_CONFLICT: 'Операция уже обрабатывается — подождите пару секунд и обновите страницу.',
  QUOTE_EXPIRED: 'Котировка устарела — обновите и попробуйте снова.',
  QUOTE_ALREADY_EXECUTED: 'Эта котировка уже исполнена.',
  INVALID_QUOTE_SIGNATURE: 'Котировка недействительна — обновите и попробуйте снова.',
  ORACLE_UNAVAILABLE: 'Сервис цен временно недоступен — попробуйте позже.',
  VALIDATION_ERROR: 'Проверьте введённые данные.',
  UNAUTHORIZED: 'Сессия истекла — войдите снова.',
  TX_FAILED: 'Не удалось провести операцию. Попробуйте позже.',
}

interface ApiErrorShape {
  response?: { data?: { errorCode?: string; message?: string }; status?: number }
}

/**
 * Friendly user-facing message for a failed API call.
 *
 * @param err      the thrown error (axios error or anything)
 * @param fallback message to use when the error code is unknown / the request
 *                 never got an HTTP response (network/CORS). Never returns the
 *                 raw backend message, which may leak internal IDs.
 */
export function apiErrorMessage(err: unknown, fallback = 'Не удалось выполнить операцию'): string {
  const code = (err as ApiErrorShape)?.response?.data?.errorCode
  if (code && FRIENDLY_BY_CODE[code]) {
    return FRIENDLY_BY_CODE[code]
  }
  return fallback
}
