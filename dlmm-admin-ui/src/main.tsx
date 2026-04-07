import React from 'react'
import ReactDOM from 'react-dom/client'
import { HashRouter } from 'react-router-dom'
import { setupMockApi } from './api/mockApi'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ConfigProvider } from 'antd'
import ruRU from 'antd/locale/ru_RU'
import App from './App'
import 'antd/dist/reset.css'
import './sber-theme.css'

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

// Setup mock API interceptor for demo mode
setupMockApi()

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
