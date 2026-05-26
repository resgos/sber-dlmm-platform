import React, { useSyncExternalStore } from 'react'
import ReactDOM from 'react-dom/client'
import { HashRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ConfigProvider, theme as antdTheme } from 'antd'
import ruRU from 'antd/locale/ru_RU'
import App from './App'
// Sprint 8 C-4 — i18n init. Side-effect import: the module configures
// i18next.use(initReactI18next).init() at load time. AntD's component
// strings are already localised via ConfigProvider locale={ruRU} below;
// this import covers app-owned copy (page titles, alerts, CTAs).
import './i18n'
import 'antd/dist/reset.css'
import './sber-theme.css'
// Sprint 9-DS-r4 P2-15 — applies the persisted theme (or OS pref) to
// <html data-theme="..."> before React mounts, so the very first paint
// is the right colour and we don't get a light→dark flash.
import { themeStore } from './store/themeStore'
themeStore.initialize()

// ── Shared brand tokens (same in both light and dark) ───────────────────────
const sberBaseToken = {
  colorPrimary: '#21A038',
  colorLink: '#21A038',
  colorSuccess: '#21A038',
  borderRadius: 8,
  fontFamily: "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif",
  fontSize: 14,
}

// ── Per-theme component overrides ───────────────────────────────────────────
const sharedComponents = {
  Button: {
    primaryColor: '#FFFFFF',
  },
  Card: {
    borderRadiusLG: 12,
  },
  Input: {
    borderRadius: 8,
  },
  Select: {
    borderRadius: 8,
  },
}

const lightConfig = {
  algorithm: antdTheme.defaultAlgorithm,
  token: {
    ...sberBaseToken,
    colorBgContainer: '#FFFFFF',
    colorBgLayout: '#FAFAF8',
    colorBorder: '#E5E7EB',
    colorText: '#1F2937',
    colorTextSecondary: '#6B7280',
  },
  components: {
    ...sharedComponents,
    Menu: {
      itemBg: '#FFFFFF',
      itemSelectedBg: '#E8F5E9',
      itemSelectedColor: '#21A038',
      itemHoverBg: '#F9FAFB',
      subMenuItemBg: '#FFFFFF',
    },
    Table: {
      headerBg: '#F9FAFB',
      rowHoverBg: '#F9FAFB',
      headerColor: '#6B7280',
    },
  },
}

const darkConfig = {
  algorithm: antdTheme.darkAlgorithm,
  token: {
    ...sberBaseToken,
    colorBgContainer: '#131820',
    colorBgLayout: '#0B0F14',
    colorBorder: 'rgba(255,255,255,0.12)',
    colorText: 'rgba(255,255,255,0.92)',
    colorTextSecondary: 'rgba(255,255,255,0.60)',
  },
  components: {
    ...sharedComponents,
    Menu: {
      itemBg: '#0F141B',
      itemSelectedBg: 'rgba(33,160,56,0.15)',
      itemSelectedColor: '#21A038',
      itemHoverBg: '#1A2029',
      subMenuItemBg: '#0F141B',
    },
    Table: {
      headerBg: '#1A2029',
      rowHoverBg: '#1A2029',
      headerColor: 'rgba(255,255,255,0.85)',
    },
  },
}

// ── QueryClient (stable singleton outside render) ────────────────────────────
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 30000,
      refetchOnWindowFocus: false,
    },
  },
})

// ── ThemedApp: subscribes ConfigProvider to themeStore ───────────────────────
// R-05: wrap App in a thin component that calls useSyncExternalStore so React
// re-renders — and AntD regenerates its CSS-in-JS tokens — the instant the
// user toggles light / dark / system in ProfilePage. Without this the
// ConfigProvider received a static theme prop and AntD's token system never
// reacted to changes, requiring !important overrides throughout sber-theme.css
// to beat AntD's CSS-in-JS specificity on dark-mode selectors.
function ThemedApp() {
  const resolvedTheme = useSyncExternalStore(
    themeStore.subscribe,
    themeStore.getResolvedSnapshot,
    // Server-snapshot (SSR safety / StrictMode double-invoke): same fn,
    // themeStore reads localStorage which is always available in the browser.
    themeStore.getResolvedSnapshot,
  )
  const themeConfig = resolvedTheme === 'dark' ? darkConfig : lightConfig

  return (
    <ConfigProvider theme={themeConfig} locale={ruRU}>
      <QueryClientProvider client={queryClient}>
        <HashRouter>
          <App />
        </HashRouter>
      </QueryClientProvider>
    </ConfigProvider>
  )
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ThemedApp />
  </React.StrictMode>,
)
