import { test, expect, SAMPLE_POOL } from './fixtures'

test.describe('Swap critical path', () => {
  test('end-to-end swap: select tokens → quote → execute → success alert', async ({ page, mockApi }) => {
    await mockApi.installBaseline()
    await mockApi.seedAuth()

    // Quote endpoint — returns a deterministic shape so the UI math is checkable.
    let quoteCallCount = 0
    await page.route('**/api/v1/pools/quote', (route) => {
      quoteCallCount += 1
      const body = route.request().postDataJSON() as { amountIn: number }
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          poolId: SAMPLE_POOL.id,
          amountIn: body.amountIn,
          amountOut: Math.floor(body.amountIn * 0.997),     // 0.3% fee
          fee: Math.floor(body.amountIn * 0.003),
          priceImpact: 0.42,
        }),
      })
    })

    // Swap execute endpoint — captures request so we can assert on payload.
    let swapBodyCapture: Record<string, unknown> | null = null
    await page.route('**/api/v1/pools/swap', (route) => {
      swapBodyCapture = route.request().postDataJSON() as Record<string, unknown>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          transactionId: 'tx-e2e-1',
          poolId: SAMPLE_POOL.id,
          amountIn: swapBodyCapture.amountIn,
          amountOut: Math.floor((swapBodyCapture.amountIn as number) * 0.997),
          fee: Math.floor((swapBodyCapture.amountIn as number) * 0.003),
          executedAt: new Date().toISOString(),
        }),
      })
    })

    await page.goto('/swap')

    // Sanity — we landed on the swap page (not bounced to login).
    await expect(page.getByText('Мгновенный своп между токенами через DLMM-пулы')).toBeVisible()

    // Pick "Вы отдаёте" token (SRUB). The Select uses AntD with optionFilterProp.
    const fromSelect = page.locator('.sber-swap-box').first().locator('.ant-select')
    await fromSelect.click()
    await page.getByRole('option', { name: /SRUB.*Sber Rouble/ }).click()

    // Pick "Вы получаете" token (SBER).
    const toSelect = page.locator('.sber-swap-box').nth(1).locator('.ant-select')
    await toSelect.click()
    await page.getByRole('option', { name: /SBER.*Sberbank/ }).click()

    // Type the amount.
    const amountInput = page.locator('.sber-swap-box').first().locator('input.ant-input-number-input')
    await amountInput.fill('10000')

    // Quote should fire and the receive box populates.
    await expect.poll(() => quoteCallCount, { timeout: 5_000 }).toBeGreaterThan(0)
    const outputInput = page.locator('.sber-swap-box').nth(1).locator('input.ant-input-number-input')
    await expect(outputInput).toHaveValue(/9.?970|9970/)

    // The quote panel renders the price-impact + fee rows.
    await expect(page.getByText('Влияние на цену')).toBeVisible()
    await expect(page.getByText('0.42%')).toBeVisible()
    await expect(page.getByText('Курс')).toBeVisible()

    // Submit.
    await page.getByRole('button', { name: 'Обменять' }).click()

    // Success alert visible.
    await expect(page.getByText('Обмен выполнен успешно!')).toBeVisible({ timeout: 5_000 })

    // Assert the swap request shape was sane — not just "anything POSTed".
    expect(swapBodyCapture).not.toBeNull()
    expect(swapBodyCapture).toMatchObject({
      poolId: SAMPLE_POOL.id,
      tokenInId: 'tok-srub',
      amountIn: 10000,
    })
    // minAmountOut respects the default 0.5% slippage: floor(9970 * 0.995) = 9920
    expect(swapBodyCapture!.minAmountOut).toBe(9920)
    // Idempotency key is a UUID — non-empty string sent on every call.
    expect(typeof swapBodyCapture!.idempotencyKey).toBe('string')
    expect((swapBodyCapture!.idempotencyKey as string).length).toBeGreaterThan(8)
  })

  test('unauthenticated visit to /swap still loads the page shell (no infinite redirect)', async ({ page, mockApi }) => {
    await mockApi.installBaseline()

    // Note: this app does NOT currently gate routes on the client (no
    // ProtectedRoute wrapper) — gateway 401s are what kick users to login.
    // This test pins that observation: visiting /swap unauthenticated renders
    // the shell without crashing. If we add a client-side guard later, this
    // test will start failing and we'll know to update it.
    await page.goto('/swap')
    await expect(page.getByText('Мгновенный своп между токенами через DLMM-пулы')).toBeVisible()
  })
})
