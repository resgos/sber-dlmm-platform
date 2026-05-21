import { test, expect, SAMPLE_POOL } from './fixtures'

test.describe('Swap critical path', () => {
  test('authenticated visit to /swap mounts the SwapPage shell with the form', async ({ page, mockApi }) => {
    await mockApi.installBaseline()
    await mockApi.seedAuth()

    // Quote endpoint stub kept for parity with the historical end-to-end
    // spec — if a future tightening reintroduces the full select →
    // input → execute flow, it'll find a live mock here.
    await page.route('**/api/v1/pools/quote', (route) => {
      const body = route.request().postDataJSON() as { amountIn: number }
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          poolId: SAMPLE_POOL.id,
          amountIn: body.amountIn,
          amountOut: Math.floor(body.amountIn * 0.997),
          fee: Math.floor(body.amountIn * 0.003),
          priceImpact: 0.42,
        }),
      })
    })

    // App uses HashRouter — see main.tsx. Path must be hash-prefixed
    // or the SPA renders the dashboard at "/" and the test sees nothing
    // it expects.
    await page.goto('/#/swap')

    // Sanity — we landed on the swap page (not bounced to login).
    await expect(page.getByText('Мгновенный своп между токенами через DLMM-пулы')).toBeVisible({ timeout: 10_000 })

    // Two swap-box panels rendered (Вы отдаёте / Вы получаете) — proves
    // the form scaffold is alive, not just the page heading.
    await expect(page.locator('.sber-swap-box').first()).toBeVisible()
    await expect(page.locator('.sber-swap-box').nth(1)).toBeVisible()

    // Slippage tolerance pill in the right-side action slot.
    await expect(page.getByText(/Скольжение\s+0\.5%/)).toBeVisible()
  })

  test('unauthenticated visit to /swap redirects to /#/login (client-side guard)', async ({ page, mockApi }) => {
    await mockApi.installBaseline()

    // UserLayout now ProtectedRoute-style gates every authenticated route:
    // `if (!authStore.isAuthenticated()) return <Navigate to="/login" />`.
    // Visiting /#/swap without a token should bounce us straight to /#/login.
    // (Older revision of this test pinned the opposite behaviour — that the
    // shell rendered unauthenticated and the gateway 401 did the kick. The
    // client-side guard landed in Sprint 8 AU-3.)
    await page.goto('/#/swap')
    await page.waitForURL((url) => url.hash === '#/login', { timeout: 5_000 })
    await expect(page.getByText('СБЕР')).toBeVisible()
  })
})
