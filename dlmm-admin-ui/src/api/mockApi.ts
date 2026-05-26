import MockAdapter from 'axios-mock-adapter'
import apiClient from './client'

// Генерация истории цен/объёмов за 30 дней
function genHistory(key: string, base: number, variance = 0.1) {
  return Array.from({ length: 30 }, (_, i) => {
    const date = new Date(2026, 1, 15 + i).toISOString().split('T')[0]
    const val = base * (1 + (Math.random() - 0.5) * variance)
    return { date, [key]: Math.round(val) }
  })
}

const POOL_DETAIL = (id: string, tokenX: string, tokenY: string) => ({
  id,
  tokenXId: `TOKEN-${tokenX}`,
  tokenYId: `TOKEN-${tokenY}`,
  tokenXSymbol: tokenX,
  tokenYSymbol: tokenY,
  binStep: 10,
  baseFeeBps: 30,
  activeBinId: 8388608,
  currentPrice: 6750000,
  totalTvlX: 2500000000,
  totalTvlY: 370.37,
  volume24h: 350000000,
  estimatedApy: 18.5,
  status: 'ACTIVE',
  createdAt: '2025-01-15T00:00:00Z',
  currentDynamicFeeBps: 32,
  volatilityAccumulator: 1250,
  totalFeesCollectedX: 7500000,
  totalFeesCollectedY: 1.12,
  totalPositions: 342,
  totalSwaps: 12450,
  protocolFeeRate: 0.0005,
  bins: Array.from({ length: 30 }, (_, i) => ({
    binId: 8388593 + i,
    price: 6700000 + i * 5000,
    liquidity: Math.floor(Math.random() * 50000000 + 1000000),
    reserveX: Math.floor(Math.random() * 100000000),
    reserveY: Math.random() * 15,
    compositionFactor: Math.random(),
  })),
})

const POOLS_MAP: Record<string, [string, string]> = {
  '1': ['SRUB', 'SBTC'],
  '2': ['SRUB', 'SETH'],
  '3': ['SBTC', 'SETH'],
  '4': ['SRUB', 'SGOLD'],
  '5': ['SRUB', 'SOIL'],
}

export function setupMockApi() {
  const mock = new MockAdapter(apiClient, { delayResponse: 200 })

  // ─── Dashboard ───────────────────────────────────────────────────────────────
  mock.onGet('/admin/dashboard').reply(200, {
    totalUsers: 12847,
    verifiedUsers: 10293,
    totalPools: 156,
    activePools: 124,
    totalTvlRub: 8540000000,
    volume24hRub: 1230000000,
    totalFeesCollectedRub: 45600000,
    transactionsToday: 34521,
    activePositions: 8934,
  })

  // ─── Users ───────────────────────────────────────────────────────────────────
  mock.onGet('/admin/users').reply(200, {
    content: [
      { id: '1', email: 'ivanov@sberbank.ru', fullName: 'Иванов А.С.', role: 'USER', kycStatus: 'VERIFIED', blocked: false, createdAt: '2025-01-15T10:00:00Z' },
      { id: '2', email: 'petrova@yandex.ru', fullName: 'Петрова Е.В.', role: 'USER', kycStatus: 'VERIFIED', blocked: false, createdAt: '2025-02-20T14:30:00Z' },
      { id: '3', email: 'sidorov@mail.ru', fullName: 'Сидоров К.М.', role: 'MARKET_MAKER', kycStatus: 'PENDING', blocked: false, createdAt: '2025-03-01T09:15:00Z' },
      { id: '4', email: 'kozlova@gmail.com', fullName: 'Козлова Н.И.', role: 'USER', kycStatus: 'VERIFIED', blocked: false, createdAt: '2025-03-05T16:45:00Z' },
      { id: '5', email: 'volkov@tinkoff.ru', fullName: 'Волков Д.А.', role: 'USER', kycStatus: 'REJECTED', blocked: true, createdAt: '2025-03-10T11:20:00Z' },
      { id: '6', email: 'smirnova@vk.com', fullName: 'Смирнова О.П.', role: 'ADMIN', kycStatus: 'VERIFIED', blocked: false, createdAt: '2025-01-10T08:00:00Z' },
      { id: '7', email: 'kuznetsov@sber.ru', fullName: 'Кузнецов И.Л.', role: 'USER', kycStatus: 'VERIFIED', blocked: false, createdAt: '2025-02-14T12:10:00Z' },
      { id: '8', email: 'novikova@rambler.ru', fullName: 'Новикова Т.Г.', role: 'USER', kycStatus: 'PENDING', blocked: false, createdAt: '2025-03-12T15:30:00Z' },
    ],
    totalElements: 12847,
    totalPages: 643,
    number: 0,
    size: 20,
  })

  // User detail — /admin/users/1, /admin/users/2, etc.
  mock.onGet(/\/admin\/users\/[^/]+$/).reply((config) => {
    const id = config.url?.split('/').pop()
    const users: Record<string, object> = {
      '1': { id: '1', email: 'ivanov@sberbank.ru', fullName: 'Иванов Алексей Сергеевич', role: 'USER', kycStatus: 'VERIFIED', blocked: false, createdAt: '2025-01-15T10:00:00Z', walletAddress: '0xAbC1d2E3f4G5h6I7j8K9L0m1N2o3P4q5R6s7T8u9' },
      '2': { id: '2', email: 'petrova@yandex.ru', fullName: 'Петрова Елена Владимировна', role: 'USER', kycStatus: 'VERIFIED', blocked: false, createdAt: '2025-02-20T14:30:00Z', walletAddress: '0xFgH2i3J4k5L6m7N8o9P0q1R2s3T4u5V6w7X8y9Z' },
      '3': { id: '3', email: 'sidorov@mail.ru', fullName: 'Сидоров Кирилл Михайлович', role: 'MARKET_MAKER', kycStatus: 'PENDING', blocked: false, createdAt: '2025-03-01T09:15:00Z', walletAddress: '0xKlM3n4O5p6Q7r8S9t0U1v2W3x4Y5z6A7b8C9d0E' },
    }
    const user = users[id ?? ''] ?? users['1']
    return [200, user]
  })

  // User transactions — /admin/users/:id/transactions
  mock.onGet(/\/admin\/users\/[^/]+\/transactions/).reply(200, {
    content: [
      { id: 'TX-000001', txType: 'SWAP', status: 'CONFIRMED', userId: '1', poolId: 'POOL-001', amountIn: 10000000, amountOut: 0.0148, feeAmount: 30000, feeRate: 0.003, binsCrossed: 3, createdAt: '2026-03-16T14:30:00Z', updatedAt: '2026-03-16T14:30:05Z', confirmedAt: '2026-03-16T14:30:05Z', tokenInId: null, tokenOutId: null, idempotencyKey: null, metadata: null, errorMessage: null },
      { id: 'TX-000002', txType: 'ADD_LIQUIDITY', status: 'CONFIRMED', userId: '1', poolId: 'POOL-002', amountIn: 5000000, amountOut: 1500, feeAmount: 15000, feeRate: 0.003, binsCrossed: 0, createdAt: '2026-03-15T11:20:00Z', updatedAt: '2026-03-15T11:20:04Z', confirmedAt: '2026-03-15T11:20:04Z', tokenInId: null, tokenOutId: null, idempotencyKey: null, metadata: null, errorMessage: null },
    ],
    totalElements: 2,
    totalPages: 1,
    number: 0,
    size: 20,
  })

  // ─── Tokens ──────────────────────────────────────────────────────────────────
  mock.onGet('/admin/tokens').reply(200, {
    content: [
      { id: '1', symbol: 'SRUB', name: 'Сбер Рубль', tokenType: 'FIAT_BACKED', decimals: 8, totalSupply: 50000000000, circulatingSupply: 42300000000, active: true, createdAt: '2025-01-01' },
      { id: '2', symbol: 'SBTC', name: 'Сбер Биткоин', tokenType: 'COMMODITY_BACKED', decimals: 8, totalSupply: 2100, circulatingSupply: 1850, active: true, createdAt: '2025-01-01' },
      { id: '3', symbol: 'SETH', name: 'Сбер Эфириум', tokenType: 'UTILITY', decimals: 18, totalSupply: 100000, circulatingSupply: 87500, active: true, createdAt: '2025-01-15' },
      { id: '4', symbol: 'SGOLD', name: 'Сбер Золото', tokenType: 'COMMODITY_BACKED', decimals: 8, totalSupply: 500000, circulatingSupply: 420000, active: true, createdAt: '2025-02-01' },
      { id: '5', symbol: 'SSILV', name: 'Сбер Серебро', tokenType: 'COMMODITY_BACKED', decimals: 8, totalSupply: 10000000, circulatingSupply: 7500000, active: false, createdAt: '2025-02-15' },
      { id: '6', symbol: 'SOIL', name: 'Сбер Нефть', tokenType: 'COMMODITY_BACKED', decimals: 8, totalSupply: 2000000, circulatingSupply: 1650000, active: true, createdAt: '2025-03-01' },
    ],
    totalElements: 24,
    totalPages: 2,
    number: 0,
    size: 20,
  })

  // Token detail — /admin/tokens/:id
  mock.onGet(/\/admin\/tokens\/[^/]+$/).reply((config) => {
    const id = config.url?.split('/').pop()
    const tokens: Record<string, object> = {
      '1': { id: '1', symbol: 'SRUB', name: 'Сбер Рубль', tokenType: 'FIAT_BACKED', decimals: 8, totalSupply: 50000000000, circulatingSupply: 42300000000, active: true, contractAddress: '0x1234567890AbCdEf1234567890AbCdEf12345678', createdAt: '2025-01-01T00:00:00Z', description: 'Стейблкоин, обеспеченный российским рублём' },
      '2': { id: '2', symbol: 'SBTC', name: 'Сбер Биткоин', tokenType: 'COMMODITY_BACKED', decimals: 8, totalSupply: 2100, circulatingSupply: 1850, active: true, contractAddress: '0x5678AbCd9012EfGh5678AbCd9012EfGh56789012', createdAt: '2025-01-01T00:00:00Z', description: 'Токен, обеспеченный биткоином' },
      '3': { id: '3', symbol: 'SETH', name: 'Сбер Эфириум', tokenType: 'UTILITY', decimals: 18, totalSupply: 100000, circulatingSupply: 87500, active: true, contractAddress: '0x9AbCdEf0123456789AbCdEf0123456789AbCdEf01', createdAt: '2025-01-15T00:00:00Z', description: 'Утилитарный токен платформы' },
    }
    const token = tokens[id ?? ''] ?? tokens['1']
    return [200, token]
  })

  // Token analytics — /admin/tokens/:id/analytics
  mock.onGet(/\/admin\/tokens\/[^/]+\/analytics/).reply(200, {
    tokenId: '1',
    priceHistory: genHistory('price', 1.0, 0.05),
    supplyHistory: genHistory('supply', 42000000000, 0.02),
    volumeHistory: genHistory('volume', 500000000, 0.3),
  })

  // ─── Pools ───────────────────────────────────────────────────────────────────
  mock.onGet('/admin/pools').reply(200, {
    content: [
      { id: '1', tokenXId: 'TOKEN-SRUB', tokenYId: 'TOKEN-SBTC', tokenXSymbol: 'SRUB', tokenYSymbol: 'SBTC', binStep: 10, baseFeeBps: 30, activeBinId: 8388608, currentPrice: 6750000, totalTvlX: 2500000000, totalTvlY: 370.37, volume24h: 350000000, estimatedApy: 18.5, status: 'ACTIVE', createdAt: '2025-01-15T00:00:00Z' },
      { id: '2', tokenXId: 'TOKEN-SRUB', tokenYId: 'TOKEN-SETH', tokenXSymbol: 'SRUB', tokenYSymbol: 'SETH', binStep: 20, baseFeeBps: 30, activeBinId: 8388608, currentPrice: 245000, totalTvlX: 1800000000, totalTvlY: 7346.94, volume24h: 280000000, estimatedApy: 22.3, status: 'ACTIVE', createdAt: '2025-01-20T00:00:00Z' },
      { id: '3', tokenXId: 'TOKEN-SBTC', tokenYId: 'TOKEN-SETH', tokenXSymbol: 'SBTC', tokenYSymbol: 'SETH', binStep: 5, baseFeeBps: 10, activeBinId: 8388608, currentPrice: 27.55, totalTvlX: 14.08, totalTvlY: 387.78, volume24h: 120000000, estimatedApy: 15.1, status: 'ACTIVE', createdAt: '2025-02-01T00:00:00Z' },
      { id: '4', tokenXId: 'TOKEN-SRUB', tokenYId: 'TOKEN-SGOLD', tokenXSymbol: 'SRUB', tokenYSymbol: 'SGOLD', binStep: 15, baseFeeBps: 50, activeBinId: 8388608, currentPrice: 7500, totalTvlX: 750000000, totalTvlY: 100000, volume24h: 95000000, estimatedApy: 12.8, status: 'ACTIVE', createdAt: '2025-02-10T00:00:00Z' },
      { id: '5', tokenXId: 'TOKEN-SRUB', tokenYId: 'TOKEN-SOIL', tokenXSymbol: 'SRUB', tokenYSymbol: 'SOIL', binStep: 25, baseFeeBps: 30, activeBinId: 8388608, currentPrice: 5600, totalTvlX: 420000000, totalTvlY: 75000, volume24h: 55000000, estimatedApy: 9.7, status: 'PAUSED', createdAt: '2025-03-01T00:00:00Z' },
    ],
    totalElements: 156,
    totalPages: 8,
    number: 0,
    size: 20,
  })

  // Pool detail — /admin/pools/:id  (before analytics route to avoid regex conflict)
  mock.onGet(/\/admin\/pools\/[^/]+$/).reply((config) => {
    const id = config.url?.split('/').pop() ?? '1'
    const pair = POOLS_MAP[id] ?? POOLS_MAP['1']
    return [200, POOL_DETAIL(id, pair[0], pair[1])]
  })

  // Pool analytics — /admin/pools/:id/analytics
  mock.onGet(/\/admin\/pools\/[^/]+\/analytics/).reply((config) => {
    const id = config.url?.split('/').slice(-2)[0] ?? '1'
    return [200, {
      poolId: id,
      tvlHistory: genHistory('tvl', 2000000000, 0.15),
      volumeHistory: genHistory('volume', 350000000, 0.3),
      feeHistory: genHistory('fees', 1050000, 0.25),
      apyHistory: genHistory('apy', 18.5, 0.1),
    }]
  })

  // Pool pause/resume/shutdown
  mock.onPost(/\/admin\/pools\/[^/]+\/(pause|resume|emergency-shutdown)/).reply(200, { message: 'OK' })

  // ─── Transactions ─────────────────────────────────────────────────────────────
  mock.onGet('/admin/transactions/suspicious').reply(200, [
    { id: 'ST-00001', type: 'SWAP', userId: 'USR-00005', poolId: 'POOL-00001', amount: 500000000, reason: 'Аномальный всплеск объёма', timestamp: '2026-03-16T13:00:00Z' },
    { id: 'ST-00002', type: 'SWAP', userId: 'USR-00005', poolId: 'POOL-00002', amount: 250000000, reason: 'Паттерн фиктивной торговли', timestamp: '2026-03-16T12:30:00Z' },
    { id: 'ST-00003', type: 'SWAP', userId: 'USR-00003', poolId: 'POOL-00003', amount: 80000000, reason: 'Быстрые последовательные сделки', timestamp: '2026-03-16T11:45:00Z' },
  ])

  mock.onGet('/admin/transactions').reply(200, {
    content: [
      { id: 'TX-000001', txType: 'SWAP', status: 'CONFIRMED', userId: 'USR-00001', poolId: 'POOL-001', amountIn: 10000000, amountOut: 0.0148, feeAmount: 30000, feeRate: 0.003, binsCrossed: 3, createdAt: '2026-03-16T14:30:00Z', updatedAt: '2026-03-16T14:30:05Z', confirmedAt: '2026-03-16T14:30:05Z', tokenInId: null, tokenOutId: null, idempotencyKey: null, metadata: null, errorMessage: null },
      { id: 'TX-000002', txType: 'ADD_LIQUIDITY', status: 'CONFIRMED', userId: 'USR-00002', poolId: 'POOL-002', amountIn: 5000000, amountOut: 1500, feeAmount: 15000, feeRate: 0.003, binsCrossed: 0, createdAt: '2026-03-16T14:25:00Z', updatedAt: '2026-03-16T14:25:04Z', confirmedAt: '2026-03-16T14:25:04Z', tokenInId: null, tokenOutId: null, idempotencyKey: null, metadata: null, errorMessage: null },
      { id: 'TX-000003', txType: 'REMOVE_LIQUIDITY', status: 'CONFIRMED', userId: 'USR-00004', poolId: 'POOL-001', amountIn: 2500000, amountOut: 0.0037, feeAmount: 7500, feeRate: 0.003, binsCrossed: 0, createdAt: '2026-03-16T14:20:00Z', updatedAt: '2026-03-16T14:20:03Z', confirmedAt: '2026-03-16T14:20:03Z', tokenInId: null, tokenOutId: null, idempotencyKey: null, metadata: null, errorMessage: null },
      { id: 'TX-000004', txType: 'SWAP', status: 'PENDING', userId: 'USR-00003', poolId: 'POOL-003', amountIn: 0.5, amountOut: 13.78, feeAmount: 0.0005, feeRate: 0.001, binsCrossed: 1, createdAt: '2026-03-16T14:15:00Z', updatedAt: '2026-03-16T14:15:00Z', confirmedAt: null, tokenInId: null, tokenOutId: null, idempotencyKey: null, metadata: null, errorMessage: null },
      { id: 'TX-000005', txType: 'SWAP', status: 'CONFIRMED', userId: 'USR-00007', poolId: 'POOL-004', amountIn: 25000000, amountOut: 3333, feeAmount: 125000, feeRate: 0.005, binsCrossed: 5, createdAt: '2026-03-16T14:10:00Z', updatedAt: '2026-03-16T14:10:06Z', confirmedAt: '2026-03-16T14:10:06Z', tokenInId: null, tokenOutId: null, idempotencyKey: null, metadata: null, errorMessage: null },
      { id: 'TX-000006', txType: 'ADD_LIQUIDITY', status: 'CONFIRMED', userId: 'USR-00006', poolId: 'POOL-001', amountIn: 100000000, amountOut: 14.81, feeAmount: 0, feeRate: 0, binsCrossed: 0, createdAt: '2026-03-16T14:05:00Z', updatedAt: '2026-03-16T14:05:02Z', confirmedAt: '2026-03-16T14:05:02Z', tokenInId: null, tokenOutId: null, idempotencyKey: null, metadata: null, errorMessage: null },
    ],
    totalElements: 34521,
    totalPages: 1727,
    number: 0,
    size: 20,
  })

  // ─── Cohort Analytics ─────────────────────────────────────────────────────────
  mock.onGet('/admin/cohorts').reply((config) => {
    const metric = (config.params?.metric as string | undefined) ?? 'DAU'
    const days   = Number(config.params?.days ?? 30)

    // Base values per metric
    const bases: Record<string, number> = { DAU: 420, MAU: 3200, D7: 42, D30: 28 }
    const base = bases[metric] ?? 420
    const variance = metric === 'D7' || metric === 'D30' ? 0.08 : 0.2

    const data = Array.from({ length: days }, (_, i) => {
      const d = new Date(2026, 4, 26 - (days - 1 - i)) // 2026-05-26 minus offset
      const date = d.toISOString().split('T')[0]
      const value = Math.max(0, Math.round(base * (1 + (Math.random() - 0.5) * variance)))
      return { date, value }
    })

    return [200, data]
  })

  // ─── Auth ─────────────────────────────────────────────────────────────────────
  mock.onPost('/auth/login').reply(200, {
    token: 'demo-admin-token',
    user: { userId: '1', email: 'admin@sber-dlmm.ru', role: 'SUPER_ADMIN' },
  })

  // ─── Catch-all ────────────────────────────────────────────────────────────────
  mock.onAny().reply(200, { message: 'OK' })
}
