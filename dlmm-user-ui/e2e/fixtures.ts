import { test as base, expect } from '@playwright/test'

/**
 * Shared mock-API helpers for e2e tests. Every test installs the same baseline
 * (tokens + balances + pools + dashboard) and then overrides specific endpoints
 * for the scenario under test. This keeps each spec readable — the test reads
 * "here is the swap I am simulating" instead of 200 lines of route setup.
 */

export const SAMPLE_TOKENS = [
  { id: 'tok-srub', symbol: 'SRUB', name: 'Sber Rouble', decimals: 6, tokenType: 'STABLECOIN' },
  { id: 'tok-sber', symbol: 'SBER', name: 'Sberbank', decimals: 6, tokenType: 'EQUITY_TOKEN' },
  { id: 'tok-gazp', symbol: 'GAZP', name: 'Gazprom', decimals: 6, tokenType: 'EQUITY_TOKEN' },
]

export const SAMPLE_POOL = {
  id: 'pool-srub-sber',
  tokenXId: 'tok-srub',
  tokenYId: 'tok-sber',
  tokenXSymbol: 'SRUB',
  tokenYSymbol: 'SBER',
  status: 'ACTIVE',
  binStep: 25,
  baseFeeBps: 30,
  activeBinId: 100,
  totalTvlX: 5_000_000,
  totalTvlY: 8_000,
  volume24h: 1_200_000,
  estimatedApy: 18.5,
}

export const SAMPLE_BALANCES = [
  { tokenId: 'tok-srub', tokenSymbol: 'SRUB', available: 1_000_000, locked: 0 },
  { tokenId: 'tok-sber', tokenSymbol: 'SBER', available: 100, locked: 0 },
  { tokenId: 'tok-gazp', tokenSymbol: 'GAZP', available: 50, locked: 0 },
]

export const SAMPLE_DASHBOARD = {
  totalUsers: 1234,
  verifiedUsers: 987,
  activePositions: 42,
  totalPools: 22,
  activePools: 20,
  totalTvlRub: 13_000_000,
  volume24hRub: 1_200_000,
  totalFeesCollectedRub: 88_000,
  transactionsToday: 17,
}

/**
 * Fake but plausible-looking JWT — three base64 segments. The user-ui never
 * decodes it (the gateway does), so a fake string satisfies the localStorage
 * read in authStore.isAuthenticated().
 */
export const FAKE_JWT =
  'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyLTEiLCJlbWFpbCI6ImRlbW9Ac2Jlci5ydSIsInJvbGUiOiJVU0VSIiwia3ljU3RhdHVzIjoiVkVSSUZJRUQifQ.fake-signature'

export interface MockApiHelpers {
  /** Install baseline GET endpoints (tokens, balances, pools, dashboard). */
  installBaseline(): Promise<void>
  /** Auto-login by seeding localStorage — skips the login page entirely. */
  seedAuth(): Promise<void>
}

type Fixtures = { mockApi: MockApiHelpers }

export const test = base.extend<Fixtures>({
  mockApi: async ({ page }, use) => {
    const helpers: MockApiHelpers = {
      async installBaseline() {
        await page.route('**/api/v1/tokens?**', (route) =>
          route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
              content: SAMPLE_TOKENS,
              totalElements: SAMPLE_TOKENS.length,
              totalPages: 1,
              size: 100,
              number: 0,
            }),
          }),
        )
        // balances API lives under /api/v1/balances/me (not /api/v1/tokens/balances/me)
        // — see dlmm-user-ui/src/api/balances.ts. Wrong glob silently floods
        // the console with proxy ECONNREFUSED in CI because Vite preview tries
        // to forward to localhost:8080.
        await page.route('**/api/v1/balances/me', (route) =>
          route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(SAMPLE_BALANCES),
          }),
        )
        await page.route('**/api/v1/pools?**', (route) =>
          route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
              content: [SAMPLE_POOL],
              totalElements: 1,
              totalPages: 1,
              size: 100,
              number: 0,
            }),
          }),
        )
        await page.route('**/api/v1/admin/dashboard', (route) =>
          route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(SAMPLE_DASHBOARD),
          }),
        )
      },

      async seedAuth() {
        // Navigate to /#/login first so we are on the right origin to write localStorage.
        // (App uses HashRouter — see main.tsx — so hash-prefixed paths are required.)
        await page.goto('/#/login')
        await page.evaluate((token) => {
          localStorage.setItem('dlmm.auth.token', token)
          localStorage.setItem(
            'dlmm.auth.user',
            JSON.stringify({
              userId: 'user-1',
              email: 'demo@sber.ru',
              role: 'USER',
              kycStatus: 'VERIFIED',
            }),
          )
        }, FAKE_JWT)
      },
    }

    await use(helpers)
  },
})

export { expect }
