import React from 'react'
import ReactDOM from 'react-dom/client'
import { HashRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ConfigProvider } from 'antd'
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

const sberTheme = {
  token: {
    colorPrimary: '#21A038',
    colorLink: '#21A038',
    colorSuccess: '#21A038',
    borderRadius: 8,
    colorBgContainer: '#FFFFFF',
    colorBorder: '#E5E7EB',
    fontFamily: "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif",
    fontSize: 14,
    colorText: '#1F2937',
    colorTextSecondary: '#6B7280',
  },
  components: {
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
  },
}

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 30000,
      refetchOnWindowFocus: false,
    },
  },
})

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider theme={sberTheme} locale={ruRU}>
      <QueryClientProvider client={queryClient}>
        <HashRouter>
          <App />
        </HashRouter>
      </QueryClientProvider>
    </ConfigProvider>
  </React.StrictMode>,
)
