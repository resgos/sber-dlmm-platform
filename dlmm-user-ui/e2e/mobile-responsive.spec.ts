import { devices } from '@playwright/test'
import { test, expect } from './fixtures'

/**
 * Mobile responsive regression — the missing piece in our toolbox.
 *
 * Chrome-MCP `resize_window` can't change the viewport (the window stays 1920),
 * so CSS media queries never fire and we could only eyeball phone bugs from
 * user screenshots. Playwright sets a REAL mobile viewport — media queries
 * evaluate at the phone width exactly as on-device — and screenshots without the
 * CDP captureScreenshot timeout that Recharts triggers. Backend is mocked
 * (fixtures), so this runs headless in CI with no docker.
 *
 * The assertion is the generic root-cause check for every "вылезает за край"
 * report: the document must not scroll horizontally (scrollWidth ≤ viewport),
 * and we name the offending elements so a failure is actionable. Extend ROUTES
 * (and the per-route mocks) as more screens get phone coverage.
 */
// Pixel 5 (not iPhone) so we stay on Chromium — the only browser installed in
// CI; iPhone profiles force WebKit. Same effect: a real 393×851 mobile viewport.
test.use({ ...devices['Pixel 5'] })

// Minimal exchange-price feed so the dashboard MarketTicker renders its card
// grid (the one that clipped its right column at two columns on phones).
const PRICE_FEED = [
  { id: 'p-sbtc', assetSymbol: 'SBTC', source: 'COINGECKO', currentPrice: 5076852, twapPrice: 5076000, priceChange24hPct: -2.75 },
  { id: 'p-seth', assetSymbol: 'SETH', source: 'COINGECKO', currentPrice: 143316, twapPrice: 143000, priceChange24hPct: 0.72 },
  { id: 'p-sgold', assetSymbol: 'SGOLD', source: 'CBR-METALS', currentPrice: 10458, twapPrice: 10400, priceChange24hPct: 3.63 },
  { id: 'p-susdt', assetSymbol: 'SUSDT', source: 'CBR-FX', currentPrice: 71.55, twapPrice: 71.5, priceChange24hPct: 0.75 },
  { id: 'p-seur', assetSymbol: 'SEUR', source: 'CBR-FX', currentPrice: 86.25, twapPrice: 86, priceChange24hPct: 4.37 },
  { id: 'p-scny', assetSymbol: 'SCNY', source: 'CBR-FX', currentPrice: 10.58, twapPrice: 10.5, priceChange24hPct: 0.86 },
]

const ROUTES: Array<[string, string]> = [
  ['dashboard', '/#/'],
  ['swap', '/#/swap'],
  ['pools', '/#/pools'],
]

test.describe('mobile @ Pixel 5 — no horizontal overflow', () => {
  for (const [name, route] of ROUTES) {
    test(`${name} fits the phone width`, async ({ page, mockApi }) => {
      await mockApi.seedAuth()
      await mockApi.installBaseline()
      await page.route('**/api/v1/oracle/prices', (r) =>
        r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(PRICE_FEED) }),
      )

      await page.goto(route)
      await page.waitForTimeout(1500) // let TanStack Query + AntD settle

      const { scrollW, clientW, offenders } = await page.evaluate(() => {
        const docW = document.documentElement.clientWidth
        const bad: string[] = []
        document.querySelectorAll<HTMLElement>('body *').forEach((el) => {
          const r = el.getBoundingClientRect()
          if (r.width > docW + 1 && r.right > docW + 1) {
            const cls = typeof el.className === 'string' ? el.className : ''
            bad.push(`${el.tagName.toLowerCase()}.${cls}`.trim().slice(0, 60))
          }
        })
        return {
          scrollW: document.documentElement.scrollWidth,
          clientW: docW,
          offenders: Array.from(new Set(bad)).slice(0, 8),
        }
      })

      await page.screenshot({ path: `mobile-${name}.png`, fullPage: true })

      expect(
        scrollW,
        `${name}: horizontal overflow (scrollWidth ${scrollW} > viewport ${clientW}). Offenders: ${offenders.join(' | ') || 'n/a'}`,
      ).toBeLessThanOrEqual(clientW + 1)
    })
  }
})
