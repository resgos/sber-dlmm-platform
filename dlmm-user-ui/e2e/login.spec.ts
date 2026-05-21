import { test, expect, FAKE_JWT } from './fixtures'

test.describe('Login flow', () => {
  test('successful login navigates to dashboard and exposes user info', async ({ page, mockApi }) => {
    await mockApi.installBaseline()

    // Mock the login endpoint — return a fake JWT + the verified-user shape.
    await page.route('**/api/v1/auth/login', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          accessToken: FAKE_JWT,
          refreshToken: 'refresh-token',
          user: {
            id: 'user-1',
            email: 'demo@sber.ru',
            role: 'USER',
            kycStatus: 'VERIFIED',
          },
        }),
      }),
    )

    // App uses HashRouter (main.tsx) so the path lives in the URL hash,
    // not the path component. Vite preview serves the SPA shell at any
    // path, so we navigate via the hash form to actually hit the
    // LoginPage component.
    await page.goto('/#/login')

    // The login page renders the Sber brand mark + form.
    await expect(page.getByText('СБЕР')).toBeVisible()
    await expect(page.getByText('DLMM')).toBeVisible()

    await page.getByLabel('Электронная почта').fill('demo@sber.ru')
    await page.getByLabel('Пароль').fill('correct-horse-battery-staple')

    await page.getByRole('button', { name: 'Войти' }).click()

    // After login we land on the dashboard at "#/" (HashRouter route "/").
    await page.waitForURL((url) => url.hash === '#/' || url.hash === '', { timeout: 10_000 })

    // Dashboard hero — "Ваш портфель" is the largest stable label on the
    // page (Claude-design hero refresh; the previous "Total Value Locked"
    // text was admin-side and never on the user dashboard).
    await expect(page.getByText('Ваш портфель')).toBeVisible({ timeout: 10_000 })

    // Auth state persisted to localStorage (so reload would keep us logged in).
    const storedToken = await page.evaluate(() => localStorage.getItem('dlmm.auth.token'))
    expect(storedToken).toBe(FAKE_JWT)
  })

  test('invalid credentials surface a red alert and stay on /login', async ({ page, mockApi }) => {
    await mockApi.installBaseline()

    await page.route('**/api/v1/auth/login', (route) =>
      route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ message: 'Неверный email или пароль' }),
      }),
    )

    await page.goto('/#/login')

    await page.getByLabel('Электронная почта').fill('demo@sber.ru')
    await page.getByLabel('Пароль').fill('wrong')
    await page.getByRole('button', { name: 'Войти' }).click()

    await expect(page.getByText('Неверный email или пароль')).toBeVisible()
    // HashRouter keeps us at #/login on failure (no navigate call).
    expect(page.url()).toContain('#/login')

    // No token was written.
    const storedToken = await page.evaluate(() => localStorage.getItem('dlmm.auth.token'))
    expect(storedToken).toBeNull()
  })
})
