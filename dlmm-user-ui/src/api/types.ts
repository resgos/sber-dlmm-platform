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

export interface RegisterRequest {
  sberId: string
  email: string
  phone: string
  firstName: string
  lastName: string
  password: string
}

// User
export type KycStatus = 'PENDING' | 'VERIFIED' | 'REJECTED' | 'NOT_SUBMITTED'
export type UserRole = 'USER' | 'OPERATOR' | 'ADMIN' | 'SUPER_ADMIN'

export interface User {
  id: string
  email: string
  firstName: string
  lastName: string
  fullName: string
  role: UserRole
  kycStatus: KycStatus
  createdAt: string
  blocked: boolean
}

export interface UpdateProfileRequest {
  firstName?: string
  lastName?: string
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

// Balances
export interface TokenBalance {
  userId: string
  tokenId: string
  symbol: string
  available: number
  locked: number
  total: number
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

// Liquidity
export type LiquidityStrategy = 'SPOT' | 'CURVE' | 'BID_ASK'

export interface Position {
  id: string
  poolId: string
  tokenXSymbol: string
  tokenYSymbol: string
  binRangeMin: number
  binRangeMax: number
  strategy: LiquidityStrategy
  totalLiquidityShares: number
  unclaimedFeeX: number
  unclaimedFeeY: number
  isActive: boolean
  createdAt: string
  closedAt: string | null
}

export interface AddLiquidityRequest {
  poolId: string
  amountX: number
  amountY: number
  binRangeMin: number
  binRangeMax: number
  strategy: LiquidityStrategy
  idempotencyKey: string
}

export interface RemoveLiquidityRequest {
  positionId: string
  percentage: number
  idempotencyKey: string
}

// Swap
export interface SwapRequest {
  poolId: string
  tokenInId: string
  amountIn: number
  minAmountOut: number
  idempotencyKey: string
}

export interface SwapQuote {
  poolId: string
  amountIn: number
  amountOut: number
  priceImpact: number
  fee: number
}

// Transaction
export type TxType =
  | 'SWAP'
  | 'ADD_LIQUIDITY'
  | 'REMOVE_LIQUIDITY'
  | 'MINT'
  | 'BURN'
  | 'TRANSFER'
  | 'CLAIM_FEE'
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

export interface TransactionFilters {
  txType?: TxType
  status?: TxStatus
  dateFrom?: string
  dateTo?: string
  poolId?: string
}

// Fees
export interface FeeSummary {
  totalEarnedX: number
  totalEarnedY: number
  totalClaimed: number
  totalUnclaimed: number
}

export interface FeeHistoryEntry {
  id: string
  positionId: string
  poolId: string
  tokenId: string
  amount: number
  claimed: boolean
  accruedAt: string
  claimedAt: string | null
}

export interface ClaimFeesRequest {
  positionId: string
}

// Notifications
export type NotificationType =
  | 'SWAP_COMPLETED'
  | 'LIQUIDITY_ADDED'
  | 'FEE_ACCRUED'
  | 'KYC_APPROVED'
  | 'SYSTEM_ALERT'
  | 'POOL_UPDATE'

export interface Notification {
  id: string
  userId: string
  type: NotificationType
  title: string
  message: string
  isRead: boolean
  createdAt: string
  readAt: string | null
}

// Oracle
export interface TokenPrice {
  tokenId: string
  symbol: string
  price: number
  change24h: number
}

// Pagination
export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
