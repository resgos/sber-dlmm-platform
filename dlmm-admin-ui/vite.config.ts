/// <reference types="vitest" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
      },
      // Sprint 10 F-15 — admin-ui API key analytics page scrapes the
      // gateway's /actuator/prometheus endpoint for the tier counters
      // (dlmm_gateway_ratelimit_total{tier,outcome}). The actuator
      // endpoint is open per JwtValidationFilter skip list; the proxy
      // entry just removes the CORS cross-origin step in dev.
      '/actuator': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        secure: false,
      },
    },
  },
  // Sprint 8 C-7 — first vitest harness for admin-ui. Mirrors user-ui setup
  // so muscle memory across both UIs stays identical (same locator helpers,
  // same matchMedia stub for AntD, same jsdom env).
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: true,
  },
})
