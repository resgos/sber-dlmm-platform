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

    await page.goto('/login')

    // The login page renders the Sber brand mark + form.
    await expect(page.getByText('СБЕР')).toBeVisible()
    await expect(page.getByText('DLMM')).toBeVisible()

    await page.getByLabel('Электронная почта').fill('demo@sber.ru')
    await page.getByLabel('Пароль').fill('correct-horse-battery-staple')

    await page.getByRole('button', { name: 'Войти' }).click()

    // After login we land on '/' which mounts the dashboard.
    await page.waitForURL((url) => url.pathname === '/', { timeout: 10_000 })

    // The dashboard renders the hero block — "Total Value Locked" is the
    // largest stable string on the page and confirms server data flowed through.
    await expect(page.getByText('Total Value Locked')).toBeVisible()

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

    await page.goto('/login')

    await page.getByLabel('Электронная почта').fill('demo@sber.ru')
    await page.getByLabel('Пароль').fill('wrong')
    await page.getByRole('button', { name: 'Войти' }).click()

    await expect(page.getByText('Неверный email или пароль')).toBeVisible()
    expect(page.url()).toContain('/login')

    // No token was written.
    const storedToken = await page.evaluate(() => localStorage.getItem('dlmm.auth.token'))
    expect(storedToken).toBeNull()
  })
})
