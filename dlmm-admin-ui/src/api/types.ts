// Auth
export interface AuthUserInfo {
  id: string
  email: string
  firstName: string
  lastName: string
  role: string
  kycStatus: string
}

export interface AuthResponse {
  accessToken: string
  refreshToken: string
  expiresIn: number
  user: AuthUserInfo
}

// User
export type KycStatus = 'PENDING' | 'VERIFIED' | 'REJECTED' | 'NOT_SUBMITTED'
export type UserRole = 'USER' | 'OPERATOR' | 'ADMIN' | 'SUPER_ADMIN'

export interface User {
  id: string
  email: string
  fullName: string
  role: UserRole
  kycStatus: KycStatus
  createdAt: string
  blocked: boolean
}

// Token
export type TokenType = 'FIAT_BACKED' | 'COMMODITY_BACKED' | 'UTILITY' | 'SECURITY'

export interface Token {
  id: string
  symbol: string
  name: string
  tokenType: TokenType
  totalSupply: number
  circulatingSupply: number
  decimals: number
  active: boolean
}

// Bin Data
export interface BinData {
  binId: number
  price: number
  liquidity: number
  reserveX: number
  reserveY: number
  compositionFactor: number
}

// Pool
export type PoolStatus = 'ACTIVE' | 'PAUSED' | 'SHUTDOWN' | 'PENDING'

export interface Pool {
  id: string
  tokenXId: string
  tokenYId: string
  tokenXSymbol: string
  tokenYSymbol: string
  binStep: number
  baseFeeBps: number
  activeBinId: number
  currentPrice: number
  totalTvlX: number
  totalTvlY: number
  volume24h: number
  estimatedApy: number
  status: PoolStatus
  createdAt: string
}

export interface PoolDetail extends Pool {
  bins: BinData[]
  volatilityAccumulator: number
  currentDynamicFeeBps: number
  totalFeesCollectedX: number
  totalFeesCollectedY: number
}

// Transaction
export type TxType =
  | 'SWAP'
  | 'ADD_LIQUIDITY'
  | 'REMOVE_LIQUIDITY'
  | 'MINT'
  | 'BURN'
  | 'TRANSFER'
export type TxStatus = 'PENDING' | 'CONFIRMED' | 'FAILED' | 'CANCELLED'

export interface Transaction {
  id: string
  txType: TxType
  status: TxStatus
  userId: string
  poolId: string | null
  tokenInId: string | null
  amountIn: number | null
  tokenOutId: string | null
  amountOut: number | null
  feeAmount: number | null
  feeRate: number | null
  binsCrossed: number | null
  idempotencyKey: string | null
  metadata: Record<string, unknown> | null
  errorMessage: string | null
  createdAt: string
  updatedAt: string
  confirmedAt: string | null
}

// Dashboard
export interface DashboardData {
  totalUsers: number
  verifiedUsers: number
  totalPools: number
  activePools: number
  totalTvlRub: number
  volume24hRub: number
  totalFeesCollectedRub: number
  activePositions: number
  transactionsToday: number
}

// Analytics
export interface PoolAnalytics {
  poolId: string
  tvlHistory: Array<{ date: string; tvl: number }>
  volumeHistory: Array<{ date: string; volume: number }>
  feeHistory: Array<{ date: string; fees: number }>
  apyHistory: Array<{ date: string; apy: number }>
}

export interface TokenAnalytics {
  tokenId: string
  priceHistory: Array<{ date: string; price: number }>
  supplyHistory: Array<{ date: string; supply: number }>
  volumeHistory: Array<{ date: string; volume: number }>
}

// Suspicious transaction
export interface SuspiciousTransaction {
  id: string
  type: string
  userId: string
  poolId: string | null
  amount: number
  reason: string
  timestamp: string
}

// Pagination
export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

// Request types
export interface CreateTokenRequest {
  symbol: string
  name: string
  tokenType: TokenType
  decimals: number
  initialSupply: number
}

export interface CreatePoolRequest {
  tokenXId: string
  tokenYId: string
  binStep: number
  baseFeeBps: number
  initialPrice: number
  maxVariableFeeBps: number
  protocolFeePct: number
  decayPeriodSeconds: number
}

export interface MintBurnRequest {
  amount: number
  userId: string
}

export interface TransactionFilters {
  txType?: TxType
  status?: TxStatus
  dateFrom?: string
  dateTo?: string
  userId?: string
  poolId?: string
}
