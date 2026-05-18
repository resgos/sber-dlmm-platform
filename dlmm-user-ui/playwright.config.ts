import { defineConfig, devices } from '@playwright/test'

/**
 * Sprint 7 e2e harness.
 *
 * Single-browser (Chromium) baseline so CI stays fast — Firefox/WebKit
 * can be added when we hit a real cross-browser bug.
 *
 * The dev server runs against vite preview of a production build (cheaper than
 * `vite dev` + HMR for CI), and all backend traffic is intercepted via
 * page.route inside each test — no docker/postgres/kafka needed to run e2e.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  timeout: 30_000,
  expect: { timeout: 5_000 },

  use: {
    baseURL: 'http://localhost:4173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],

  webServer: {
    // `vite preview` serves the production build on 4173 — no dev-server proxy
    // (we mock the /api endpoints per test). `npm run build` runs first.
    command: 'npm run build && npm run preview -- --port 4173 --strictPort',
    url: 'http://localhost:4173',
    reuseExistingServer: !process.env.CI,
    timeout: 180_000,
    stdout: 'pipe',
    stderr: 'pipe',
  },
})
