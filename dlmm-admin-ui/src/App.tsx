import { Routes, Route, Navigate } from 'react-router-dom'
import ProtectedLayout from '@/components/ProtectedLayout'
import LoginPage from '@/pages/LoginPage'
import DashboardPage from '@/pages/DashboardPage'
import UsersPage from '@/pages/UsersPage'
import UserDetailPage from '@/pages/UserDetailPage'
import TokensPage from '@/pages/TokensPage'
import TokenCreatePage from '@/pages/TokenCreatePage'
import TokenDetailPage from '@/pages/TokenDetailPage'
import PoolsPage from '@/pages/PoolsPage'
import PoolCreatePage from '@/pages/PoolCreatePage'
import PoolDetailPage from '@/pages/PoolDetailPage'
import TransactionsPage from '@/pages/TransactionsPage'
import SuspiciousTransactionsPage from '@/pages/SuspiciousTransactionsPage'
import OtcDeskPage from '@/pages/OtcDeskPage'
import ApiAnalyticsPage from '@/pages/ApiAnalyticsPage'
import SettingsPage from '@/pages/SettingsPage'
import CohortsPage from '@/pages/CohortsPage'

function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/" element={<ProtectedLayout />}>
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="dashboard" element={<DashboardPage />} />
        <Route path="users" element={<UsersPage />} />
        <Route path="users/:id" element={<UserDetailPage />} />
        <Route path="tokens" element={<TokensPage />} />
        <Route path="tokens/create" element={<TokenCreatePage />} />
        <Route path="tokens/:id" element={<TokenDetailPage />} />
        <Route path="pools" element={<PoolsPage />} />
        <Route path="pools/create" element={<PoolCreatePage />} />
        <Route path="pools/:id" element={<PoolDetailPage />} />
        <Route path="transactions" element={<TransactionsPage />} />
        <Route path="transactions/suspicious" element={<SuspiciousTransactionsPage />} />
        <Route path="otc" element={<OtcDeskPage />} />
        <Route path="api-analytics" element={<ApiAnalyticsPage />} />
        <Route path="cohorts" element={<CohortsPage />} />
        <Route path="settings" element={<SettingsPage />} />
        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Route>
    </Routes>
  )
}

export default App
